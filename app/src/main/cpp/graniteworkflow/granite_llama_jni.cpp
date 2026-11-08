#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <memory>
#include <chrono>
#include <exception>

#define LOG_TAG "GraniteLlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#if defined(GRANITE_HAS_LLAMA_CPP)
#include "llama.h"

struct GraniteLlamaSession {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    int n_ctx = 4096;
    int n_threads = 4;

    ~GraniteLlamaSession() {
        if (ctx) {
            llama_free(ctx);
            ctx = nullptr;
        }
        if (model) {
            llama_model_free(model);
            model = nullptr;
        }
    }
};
#endif

extern "C" JNIEXPORT jlong JNICALL
Java_com_n8rtz_winetracker_debug_graniteworkflow_GraniteLlamaBridge_nativeInitModel(
        JNIEnv * env,
        jclass /* clazz */,
        jstring model_path,
        jint n_ctx,
        jint n_threads) {
#if defined(GRANITE_HAS_LLAMA_CPP)
    try {
        const char * path_cstr = env->GetStringUTFChars(model_path, nullptr);
        std::string path(path_cstr ? path_cstr : "");
        if (path_cstr) env->ReleaseStringUTFChars(model_path, path_cstr);

        auto t0 = std::chrono::steady_clock::now();
        llama_backend_init();
        llama_model_params mparams = llama_model_default_params();
        mparams.n_gpu_layers = 0; // CPU arm64 NEON dotprod/fp16 inference

        llama_model * model = llama_model_load_from_file(path.c_str(), mparams);
        if (!model) {
            LOGE("Failed to load GGUF model from %s", path.c_str());
            return 0L;
        }

        llama_context_params cparams = llama_context_default_params();
        cparams.n_ctx = n_ctx > 512 ? n_ctx : 4096;
        cparams.n_batch = cparams.n_ctx;
        cparams.n_ubatch = 512;
        cparams.n_threads = n_threads > 0 ? n_threads : 4;
        cparams.n_threads_batch = cparams.n_threads;

        llama_context * ctx = llama_init_from_model(model, cparams);
        if (!ctx) {
            LOGE("Failed to create llama_context for %s", path.c_str());
            llama_model_free(model);
            return 0L;
        }

        auto t1 = std::chrono::steady_clock::now();
        auto load_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();

        auto * session = new GraniteLlamaSession();
        session->model = model;
        session->ctx = ctx;
        session->n_ctx = cparams.n_ctx;
        session->n_threads = cparams.n_threads;
        LOGI("Initialized Granite GGUF session (%s, n_ctx=%d, threads=%d) in %lld ms",
             path.c_str(), cparams.n_ctx, session->n_threads, static_cast<long long>(load_ms));
        return reinterpret_cast<jlong>(session);
    } catch (const std::exception & e) {
        LOGE("Exception in nativeInitModel: %s", e.what());
        return 0L;
    }
#else
    (void) env;
    (void) model_path;
    (void) n_ctx;
    (void) n_threads;
    LOGE("GraniteLlamaJNI compiled without GRANITE_HAS_LLAMA_CPP");
    return 0L;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_n8rtz_winetracker_debug_graniteworkflow_GraniteLlamaBridge_nativeComplete(
        JNIEnv * env,
        jclass /* clazz */,
        jlong model_handle,
        jstring prompt_jstr,
        jstring grammar_jstr,
        jint max_tokens,
        jfloat temperature) {
#if defined(GRANITE_HAS_LLAMA_CPP)
    try {
        (void) grammar_jstr;
        auto * session = reinterpret_cast<GraniteLlamaSession *>(model_handle);
        if (!session || !session->ctx || !session->model) {
            return env->NewStringUTF("");
        }

        const char * prompt_c = env->GetStringUTFChars(prompt_jstr, nullptr);
        std::string prompt(prompt_c ? prompt_c : "");
        if (prompt_c) env->ReleaseStringUTFChars(prompt_jstr, prompt_c);

        auto t_start = std::chrono::steady_clock::now();
        const llama_vocab * vocab = llama_model_get_vocab(session->model);
        llama_memory_clear(llama_get_memory(session->ctx), true);

        const int n_prompt_max = static_cast<int>(prompt.size()) + 32;
        std::vector<llama_token> tokens(n_prompt_max);
        int n_tokens = llama_tokenize(
                vocab,
                prompt.c_str(),
                static_cast<int32_t>(prompt.size()),
                tokens.data(),
                n_prompt_max,
                true,
                true);
        if (n_tokens < 0) {
            tokens.resize(-n_tokens);
            n_tokens = llama_tokenize(
                    vocab,
                    prompt.c_str(),
                    static_cast<int32_t>(prompt.size()),
                    tokens.data(),
                    static_cast<int32_t>(tokens.size()),
                    true,
                    true);
        }
        if (n_tokens <= 0) {
            LOGE("Failed to tokenize Granite prompt");
            return env->NewStringUTF("");
        }
        if (n_tokens > session->n_ctx - 128) {
            n_tokens = session->n_ctx - 128;
        }
        tokens.resize(n_tokens);

        llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
        if (llama_decode(session->ctx, batch) != 0) {
            LOGE("llama_decode failed on prompt prefill (n_tokens=%d)", n_tokens);
            return env->NewStringUTF("");
        }
        auto t_prefill = std::chrono::steady_clock::now();

        llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
        llama_sampler * sampler = llama_sampler_chain_init(sparams);

        if (temperature <= 0.01f) {
            llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
        } else {
            llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
            llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
        }

        const bool expect_closing_think =
            prompt.rfind("<think>\n") != std::string::npos &&
            prompt.rfind("</think>") == std::string::npos;

        std::string output;
        const int limit = max_tokens > 0 ? max_tokens : 512;
        int generated = 0;
        int json_brace_depth = 0;
        bool json_started = false;
        bool in_json_string = false;
        bool json_escape = false;

        for (int i = 0; i < limit; ++i) {
            // Note: llama_sampler_sample internally calls llama_sampler_accept(sampler, next)
            llama_token next = llama_sampler_sample(sampler, session->ctx, -1);
            if (llama_vocab_is_eog(vocab, next)) {
                break;
            }

            char piece[256];
            int n_piece = llama_token_to_piece(vocab, next, piece, sizeof(piece), 0, true);
            if (n_piece > 0) {
                output.append(piece, n_piece);
                const bool past_think = !expect_closing_think || (output.find("</think>") != std::string::npos);
                if (past_think) {
                    for (int c = 0; c < n_piece; ++c) {
                        char ch = piece[c];
                        if (json_started) {
                            if (json_escape) {
                                json_escape = false;
                            } else if (ch == '\\' && in_json_string) {
                                json_escape = true;
                            } else if (ch == '"') {
                                in_json_string = !in_json_string;
                            } else if (!in_json_string) {
                                if (ch == '{') json_brace_depth++;
                                else if (ch == '}') json_brace_depth--;
                            }
                        } else if (ch == '{') {
                            json_started = true;
                            json_brace_depth = 1;
                        }
                    }
                }
            }
            generated++;

            if (json_started && json_brace_depth <= 0) {
                break;
            }

            llama_batch next_batch = llama_batch_get_one(&next, 1);
            if (llama_decode(session->ctx, next_batch) != 0) {
                LOGE("llama_decode failed at generated token %d", i);
                break;
            }
        }

        auto t_end = std::chrono::steady_clock::now();
        auto prefill_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t_prefill - t_start).count();
        auto decode_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t_end - t_prefill).count();
        LOGI("Granite completion finished: prompt_tokens=%d (prefill=%lld ms), generated_tokens=%d (decode=%lld ms)",
             n_tokens, static_cast<long long>(prefill_ms), generated, static_cast<long long>(decode_ms));

        llama_sampler_free(sampler);
        return env->NewStringUTF(output.c_str());
    } catch (const std::exception & e) {
        LOGE("Exception in nativeComplete: %s", e.what());
        return env->NewStringUTF("");
    }
#else
    (void) model_handle;
    (void) prompt_jstr;
    (void) grammar_jstr;
    (void) max_tokens;
    (void) temperature;
    return env->NewStringUTF("");
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_n8rtz_winetracker_debug_graniteworkflow_GraniteLlamaBridge_nativeFreeModel(
        JNIEnv * /* env */,
        jclass /* clazz */,
        jlong model_handle) {
#if defined(GRANITE_HAS_LLAMA_CPP)
    try {
        auto * session = reinterpret_cast<GraniteLlamaSession *>(model_handle);
        delete session;
    } catch (const std::exception & e) {
        LOGE("Exception in nativeFreeModel: %s", e.what());
    }
#else
    (void) model_handle;
#endif
}
