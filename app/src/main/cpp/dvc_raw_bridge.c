#include <jni.h>

#define AUDIO_EFFECT_ERROR_INVALID_OPERATION (-5)
#define BRIDGE_ERROR_METHOD_NOT_FOUND (-1002)
#define BRIDGE_ERROR_CALL_FAILED (-1003)

static jmethodID audio_effect_command_method;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK || env == NULL) {
        return JNI_ERR;
    }

    // Poweramp performs this lookup during native-library initialization and caches the method.
    // Resolving it here also keeps hidden-framework lookup out of an app Java/native call stack.
    jclass effect_class = (*env)->FindClass(env, "android/media/audiofx/AudioEffect");
    if (effect_class != NULL) {
        audio_effect_command_method = (*env)->GetMethodID(
                env,
                effect_class,
                "command",
                "(I[B[B)I");
        (*env)->DeleteLocalRef(env, effect_class);
    }
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
    return JNI_VERSION_1_6;
}

JNIEXPORT jint JNICALL
Java_com_example_globalpeq_PowerampDvcRawBridge_nativeCommand(
        JNIEnv *env,
        jclass clazz,
        jobject effect,
        jint command_code,
        jbyteArray command,
        jbyteArray reply) {
    (void) clazz;
    if (effect == NULL || command == NULL || reply == NULL) {
        return AUDIO_EFFECT_ERROR_INVALID_OPERATION;
    }

    if (audio_effect_command_method == NULL) {
        return BRIDGE_ERROR_METHOD_NOT_FOUND;
    }

    jint result = (*env)->CallIntMethod(
            env,
            effect,
            audio_effect_command_method,
            command_code,
            command,
            reply);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return BRIDGE_ERROR_CALL_FAILED;
    }
    return result;
}
