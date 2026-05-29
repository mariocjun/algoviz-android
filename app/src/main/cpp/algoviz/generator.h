// Hand-rolled C++20 coroutine generator.
//
// Why hand-rolled: std::generator (P2502) only landed in libc++ 19; the NDK
// r26b toolchain ships Clang 17 / libc++ 17, which has <coroutine> but NOT
// std::generator. This is the minimal lazy pull-generator the sort engine
// needs — single-value yield, move-only, RAII frame ownership.
//
// Usage:
//   Generator<Step> g = bubble_sort(data);
//   while (g.next()) { const Step& s = g.value(); ... }
//
// The coroutine starts suspended (initial_suspend == suspend_always); the
// first next() resumes it to the first co_yield. final_suspend is
// suspend_always so the handle stays valid (done()==true) until ~Generator
// destroys the frame — reading value() after the last yield is safe, calling
// next() past completion simply returns false.
#pragma once

#include <coroutine>
#include <exception>
#include <utility>

namespace algoviz {

template <typename T>
class Generator {
public:
    struct promise_type {
        T current_{};

        Generator get_return_object() {
            return Generator{std::coroutine_handle<promise_type>::from_promise(*this)};
        }
        std::suspend_always initial_suspend() noexcept { return {}; }
        std::suspend_always final_suspend() noexcept { return {}; }
        std::suspend_always yield_value(T value) noexcept {
            current_ = std::move(value);
            return {};
        }
        void return_void() noexcept {}
        // Sorts never throw (int data, no user callbacks). A bad_alloc from the
        // aux buffers is unrecoverable mid-sort anyway, so terminate is honest.
        [[noreturn]] void unhandled_exception() { std::terminate(); }
    };

    using handle_type = std::coroutine_handle<promise_type>;

    Generator() noexcept = default;
    explicit Generator(handle_type h) noexcept : h_(h) {}

    Generator(const Generator&) = delete;
    Generator& operator=(const Generator&) = delete;

    Generator(Generator&& o) noexcept : h_(std::exchange(o.h_, {})) {}
    Generator& operator=(Generator&& o) noexcept {
        if (this != &o) {
            if (h_) h_.destroy();
            h_ = std::exchange(o.h_, {});
        }
        return *this;
    }

    ~Generator() {
        if (h_) h_.destroy();
    }

    // Resume to the next yielded value. Returns false once the coroutine has
    // run to completion (no further values).
    bool next() {
        if (!h_ || h_.done()) return false;
        h_.resume();
        return !h_.done();
    }

    const T& value() const { return h_.promise().current_; }
    T& value() { return h_.promise().current_; }

    bool done() const noexcept { return !h_ || h_.done(); }

private:
    handle_type h_{};
};

} // namespace algoviz
