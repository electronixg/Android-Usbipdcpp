#pragma once

#include <spdlog/sinks/base_sink.h>
#include <spdlog/spdlog.h>
#include <mutex>
#include <string>

// Custom sink forwarding logs to Kotlin through JNI callbacks
template<typename Mutex>
class jni_callback_sink : public spdlog::sinks::base_sink<Mutex> {
public:
    // Use a function pointer rather than std::function: sink_it_ is the hot path for every log, excluding
    // capturing lambdas (which introduce heap allocation) at the type level. Before supporting capturing lambdas in the future, consider
    // the allocation cost on this hot path before changing this.
    // Contract: the argument must be a forwarding function using global lookup (currently jni_log::log_callback),
    // and must not bind a specific callback at construction: replacing the callback (setLogCallback) does not recreate existing
    // sinks; always delivering logs to the current callback relies on this indirection.
    using callback_fn = void (*)(spdlog::level::level_enum, const std::string&);

    explicit jni_callback_sink(callback_fn callback) : callback_(std::move(callback)) {}

protected:
    void sink_it_(const spdlog::details::log_msg& msg) override {
        if (!callback_) return;

        // Formatting and std::string construction may throw bad_alloc, and callback_ may also throw internally;
        // upstream has call sites invoking spdlog::info outside JNI methods' try blocks (such as server startup
        // logs). An exception escaping there would cross the extern "C" boundary and immediately terminate. On failure, drop only
        // this log; it must not escalate to certain process termination.
        try {
            spdlog::memory_buf_t formatted;
            this->formatter_->format(msg, formatted);
            std::string log_str = fmt::to_string(formatted);

            callback_(msg.level, log_str);
        } catch (...) {
            // Do not log here (that would reenter this sink); simply discard.
        }
    }

    void flush_() override {}

private:
    callback_fn callback_;
};

using jni_callback_sink_mt = jni_callback_sink<std::mutex>;