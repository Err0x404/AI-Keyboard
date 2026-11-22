#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include <rime_api.h>

namespace {

jstring NewString(JNIEnv* env, const std::string& value) {
    if (env == nullptr) return nullptr;

    std::vector<jchar> utf16;
    for (size_t index = 0; index < value.size();) {
        const unsigned char first = static_cast<unsigned char>(value[index]);
        uint32_t code_point = 0;
        size_t sequence_length = 0;
        if (first < 0x80) {
            code_point = first;
            sequence_length = 1;
        } else if ((first & 0xE0) == 0xC0) {
            code_point = first & 0x1F;
            sequence_length = 2;
        } else if ((first & 0xF0) == 0xE0) {
            code_point = first & 0x0F;
            sequence_length = 3;
        } else if ((first & 0xF8) == 0xF0) {
            code_point = first & 0x07;
            sequence_length = 4;
        }

        bool valid = sequence_length != 0 && index + sequence_length <= value.size();
        for (size_t offset = 1; valid && offset < sequence_length; ++offset) {
            const unsigned char continuation =
                static_cast<unsigned char>(value[index + offset]);
            valid = (continuation & 0xC0) == 0x80;
            if (valid) code_point = (code_point << 6) | (continuation & 0x3F);
        }
        if (!valid ||
            (sequence_length == 2 && code_point < 0x80) ||
            (sequence_length == 3 && code_point < 0x800) ||
            (sequence_length == 4 && code_point < 0x10000) ||
            code_point > 0x10FFFF ||
            (code_point >= 0xD800 && code_point <= 0xDFFF)) {
            code_point = 0xFFFD;
            sequence_length = 1;
        }

        if (code_point <= 0xFFFF) {
            utf16.push_back(static_cast<jchar>(code_point));
        } else {
            code_point -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xD800 + (code_point >> 10)));
            utf16.push_back(static_cast<jchar>(0xDC00 + (code_point & 0x3FF)));
        }
        index += sequence_length;
    }

    const jchar empty = 0;
    const jchar* chars = utf16.empty() ? &empty : utf16.data();
    return env->NewString(chars, static_cast<jsize>(utf16.size()));
}

#if defined(RIME_LINKED)

RimeApi* rime_api = nullptr;
RimeSessionId session_id = 0;
bool initialized = false;
std::mutex rime_mutex;

bool CopyString(JNIEnv* env, jstring value, std::string* output) {
    if (env == nullptr || value == nullptr || output == nullptr) return false;
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return false;
    output->assign(chars);
    env->ReleaseStringUTFChars(value, chars);
    return true;
}

#endif

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_ime_RimeJniBridge_nativeInitRime(
    JNIEnv* env,
    jobject,
    jstring shared_data_dir,
    jstring user_data_dir) {
#if defined(RIME_LINKED)
    std::string shared_path;
    std::string user_path;
    if (!CopyString(env, shared_data_dir, &shared_path) ||
        !CopyString(env, user_data_dir, &user_path) ||
        shared_path.empty() || user_path.empty()) {
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> lock(rime_mutex);
    if (initialized) return JNI_TRUE;

    rime_api = rime_get_api();
    if (rime_api == nullptr ||
        !RIME_API_AVAILABLE(rime_api, setup) ||
        !RIME_API_AVAILABLE(rime_api, initialize) ||
        !RIME_API_AVAILABLE(rime_api, start_maintenance) ||
        !RIME_API_AVAILABLE(rime_api, join_maintenance_thread) ||
        !RIME_API_AVAILABLE(rime_api, create_session) ||
        !RIME_API_AVAILABLE(rime_api, destroy_session) ||
        !RIME_API_AVAILABLE(rime_api, finalize) ||
        !RIME_API_AVAILABLE(rime_api, process_key) ||
        !RIME_API_AVAILABLE(rime_api, select_candidate_on_current_page) ||
        !RIME_API_AVAILABLE(rime_api, get_context) ||
        !RIME_API_AVAILABLE(rime_api, free_context) ||
        !RIME_API_AVAILABLE(rime_api, set_option) ||
        !RIME_API_AVAILABLE(rime_api, get_option) ||
        !RIME_API_AVAILABLE(rime_api, select_schema) ||
        !RIME_API_AVAILABLE(rime_api, clear_composition)) {
        rime_api = nullptr;
        return JNI_FALSE;
    }

    RIME_STRUCT(RimeTraits, traits);
    traits.shared_data_dir = shared_path.c_str();
    traits.user_data_dir = user_path.c_str();
    traits.app_name = "rime.ai_keyboard";
    rime_api->setup(&traits);
    rime_api->initialize(&traits);
    rime_api->start_maintenance(False);
    rime_api->join_maintenance_thread();
    session_id = rime_api->create_session();
    initialized = session_id != 0;
    if (!initialized) {
        rime_api->finalize();
        rime_api = nullptr;
    }
    return initialized ? JNI_TRUE : JNI_FALSE;
#else
    (void)env;
    (void)shared_data_dir;
    (void)user_data_dir;
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_ime_RimeJniBridge_nativeProcessKey(
    JNIEnv*,
    jobject,
    jint key_code,
    jint mask) {
#if defined(RIME_LINKED)
    std::lock_guard<std::mutex> lock(rime_mutex);
    if (!initialized || rime_api == nullptr || session_id == 0) return JNI_FALSE;
    return rime_api->process_key(session_id, key_code, mask) ? JNI_TRUE : JNI_FALSE;
#else
    (void)key_code;
    (void)mask;
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_ime_RimeJniBridge_nativeSelectCandidate(
    JNIEnv*,
    jobject,
    jint index) {
#if defined(RIME_LINKED)
    if (index < 0) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(rime_mutex);
    if (!initialized || rime_api == nullptr || session_id == 0) return JNI_FALSE;
    return rime_api->select_candidate_on_current_page(
        session_id,
        static_cast<size_t>(index)) ? JNI_TRUE : JNI_FALSE;
#else
    (void)index;
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_ime_RimeJniBridge_nativeGetCompositionAndCandidates(
    JNIEnv* env,
    jobject) {
#if defined(RIME_LINKED)
    std::lock_guard<std::mutex> lock(rime_mutex);
    if (!initialized || rime_api == nullptr || session_id == 0) {
        return NewString(env, "\n");
    }

    RIME_STRUCT(RimeContext, context);
    if (!rime_api->get_context(session_id, &context)) return NewString(env, "\n");

    std::string result = context.composition.preedit != nullptr
        ? context.composition.preedit
        : "";
    result.push_back('\n');
    for (int index = 0;
         context.menu.candidates != nullptr && index < context.menu.num_candidates;
         ++index) {
        if (index > 0) result.push_back('\n');
        const char* candidate = context.menu.candidates[index].text;
        if (candidate != nullptr) result.append(candidate);
    }
    rime_api->free_context(&context);
    return NewString(env, result);
#else
    return NewString(env, "\n");
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_ime_RimeJniBridge_nativeSetOption(
    JNIEnv* env,
    jobject,
    jstring option,
    jboolean value) {
#if defined(RIME_LINKED)
    std::lock_guard<std::mutex> lock(rime_mutex);
    std::string option_name;
    if (!initialized || rime_api == nullptr || session_id == 0 ||
        !CopyString(env, option, &option_name) || option_name.empty()) {
        return JNI_FALSE;
    }
    const bool requested_value = value == JNI_TRUE;
    rime_api->set_option(session_id, option_name.c_str(), requested_value);
    return rime_api->get_option(session_id, option_name.c_str()) == requested_value
        ? JNI_TRUE
        : JNI_FALSE;
#else
    (void)env;
    (void)option;
    (void)value;
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_ime_RimeJniBridge_nativeGetOption(
    JNIEnv* env,
    jobject,
    jstring option) {
#if defined(RIME_LINKED)
    std::lock_guard<std::mutex> lock(rime_mutex);
    std::string option_name;
    if (!initialized || rime_api == nullptr || session_id == 0 ||
        !CopyString(env, option, &option_name) || option_name.empty()) {
        return JNI_FALSE;
    }
    return rime_api->get_option(session_id, option_name.c_str()) ? JNI_TRUE : JNI_FALSE;
#else
    (void)env;
    (void)option;
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_ime_RimeJniBridge_nativeSelectSchema(
    JNIEnv* env,
    jobject,
    jstring schema_id) {
#if defined(RIME_LINKED)
    std::lock_guard<std::mutex> lock(rime_mutex);
    std::string schema;
    if (!initialized || rime_api == nullptr || session_id == 0 ||
        !CopyString(env, schema_id, &schema) || schema.empty()) {
        return JNI_FALSE;
    }
    const Bool selected = rime_api->select_schema(session_id, schema.c_str());
    if (selected) {
        rime_api->clear_composition(session_id);
    }
    return selected ? JNI_TRUE : JNI_FALSE;
#else
    (void)env;
    (void)schema_id;
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_ime_RimeJniBridge_nativeClearComposition(JNIEnv*, jobject) {
#if defined(RIME_LINKED)
    std::lock_guard<std::mutex> lock(rime_mutex);
    if (initialized && rime_api != nullptr && session_id != 0) {
        rime_api->clear_composition(session_id);
    }
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_ime_RimeJniBridge_nativeDestroyRime(JNIEnv*, jobject) {
#if defined(RIME_LINKED)
    std::lock_guard<std::mutex> lock(rime_mutex);
    if (!initialized || rime_api == nullptr) return;
    if (session_id != 0) rime_api->destroy_session(session_id);
    session_id = 0;
    rime_api->finalize();
    rime_api = nullptr;
    initialized = false;
#endif
}
