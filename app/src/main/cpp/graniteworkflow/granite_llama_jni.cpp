#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <memory>

#define LOG_TAG "GraniteLlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#if defined(GRANITE_HAS_LLAMA_CPP)
#include "llama.h"

struct GraniteLlamaSession {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
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
    const char * path_cstr = env->GetStringUTFChars(model_path, nullptr);
    std::string path(path_cstr ? path_cstr : "");
    if (path_cstr) env->ReleaseStringUTFChars(model_path, path_cstr);

    llama_backend_init();
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU arm64 inference for deterministic mobile execution

    llama_model * model = llama_model_load_from_file(path.c_str(), mparams);
    if (!model) {
        LOGE("Failed to load GGUF model from %s", path.c_str());
        return 0L;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = n_ctx > 512 ? n_ctx : 4096;
    cparams.n_threads = n_threads > 0 ? n_threads : 4;
    cparams.n_threads_batch = cparams.n_threads;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("Failed to create llama_context for %s", path.c_str());
        llama_model_free(model);
        return 0L;
    }

    auto * session = new GraniteLlamaSession();
    session->model = model;
    session->ctx = ctx;
    session->n_threads = cparams.n_threads;
    LOGI("Initialized Granite GGUF session (%s, n_ctx=%d, threads=%d)",
         path.c_str(), cparams.n_ctx, session->n_threads);
    return reinterpret_cast<jlong>(session);
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
    auto * session = reinterpret_cast<GraniteLlamaSession *>(model_handle);
    if (!session || !session->ctx || !session->model) {
        return env->NewStringUTF("");
    }

    const char * prompt_c = env->GetStringUTFChars(prompt_jstr, nullptr);
    std::string prompt(prompt_c ? prompt_c : "");
    if (prompt_c) env->ReleaseStringUTFChars(prompt_jstr, prompt_c);

    const char * grammar_c = env->GetStringUTFChars(grammar_jstr, nullptr);
    std::string grammar(grammar_c ? grammar_c : "");
    if (grammar_c) env->ReleaseStringUTFChars(grammar_jstr, grammar_c);

    const llama_vocab * vocab = llama_model_get_vocab(session->model);
    llama_kv_self_clear(session->ctx);

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
    tokens.resize(n_tokens);

    llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
    if (llama_decode(session->ctx, batch) != 0) {
        LOGE("llama_decode failed on prompt prefill");
        return env->NewStringUTF("");
    }

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler * sampler = llama_sampler_chain_init(sparams);

    if (!grammar.empty()) {
        llama_sampler * g_sampler = llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
        if (g_sampler) {
            llama_sampler_chain_add(sampler, g_sampler);
        }
    }

    if (temperature <= 0.01f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    std::string output;
    const int limit = max_tokens > 0 ? max_tokens : 512;
    for (int i = 0; i < limit; ++i) {
        llama_token next = llama_sampler_sample(sampler, session->ctx, -1);
        if (llama_vocab_is_eog(vocab, next)) {
            break;
        }
        llama_sampler_accept(sampler, next);

        char piece[256];
        int n_piece = llama_token_to_piece(vocab, next, piece, sizeof(piece), 0, true);
        if (n_piece > 0) {
            output.append(piece, n_piece);
        }

        llama_batch next_batch = llama_batch_get_one(&next, 1);
        if (llama_decode(session->ctx, next_batch) != 0) {
            break;
        }
    }

    llama_sampler_free(sampler);
    return env->NewStringUTF(output.c_str());
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
    auto * session = reinterpret_cast<GraniteLlamaSession *>(model_handle);
    delete session;
#else
    (void) model_handle;
#endif
}
