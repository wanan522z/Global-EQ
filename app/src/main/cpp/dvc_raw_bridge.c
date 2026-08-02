#include <jni.h>

#define AUDIO_EFFECT_ERROR_INVALID_OPERATION (-5)

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

    jclass effect_class = (*env)->GetObjectClass(env, effect);
    if (effect_class == NULL) {
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
        return AUDIO_EFFECT_ERROR_INVALID_OPERATION;
    }

    jmethodID command_method = (*env)->GetMethodID(
            env,
            effect_class,
            "command",
            "(I[B[B)I");
    (*env)->DeleteLocalRef(env, effect_class);
    if (command_method == NULL) {
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
        return AUDIO_EFFECT_ERROR_INVALID_OPERATION;
    }

    jint result = (*env)->CallIntMethod(
            env,
            effect,
            command_method,
            command_code,
            command,
            reply);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return AUDIO_EFFECT_ERROR_INVALID_OPERATION;
    }
    return result;
}
