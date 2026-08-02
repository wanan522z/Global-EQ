#include <jni.h>

#define AUDIO_EFFECT_ERROR_INVALID_OPERATION (-5)
#define BRIDGE_ERROR_CLASS_NOT_FOUND (-1001)
#define BRIDGE_ERROR_METHOD_NOT_FOUND (-1002)
#define BRIDGE_ERROR_CALL_FAILED (-1003)

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

    // command() is declared on AudioEffect itself. Looking it up on the DynamicsProcessing
    // subclass fails on Android releases where the hidden method is private and therefore not
    // inherited in JNI method lookup. Poweramp resolves the declaring framework class directly.
    jclass effect_class = (*env)->FindClass(env, "android/media/audiofx/AudioEffect");
    if (effect_class == NULL) {
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
        return BRIDGE_ERROR_CLASS_NOT_FOUND;
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
        return BRIDGE_ERROR_METHOD_NOT_FOUND;
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
        return BRIDGE_ERROR_CALL_FAILED;
    }
    return result;
}
