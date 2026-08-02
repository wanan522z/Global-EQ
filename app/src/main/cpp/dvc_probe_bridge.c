#include <jni.h>

#define ERROR_INVALID_OPERATION (-5)

static jmethodID audio_effect_command;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK || env == NULL) {
        return JNI_ERR;
    }
    jclass effect_class = (*env)->FindClass(env, "android/media/audiofx/AudioEffect");
    if (effect_class != NULL) {
        audio_effect_command = (*env)->GetMethodID(env, effect_class, "command", "(I[B[B)I");
        (*env)->DeleteLocalRef(env, effect_class);
    }
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
    return JNI_VERSION_1_6;
}

JNIEXPORT jint JNICALL
Java_com_example_globalpeq_DvcRawSnapshotReceiver_nativeCommand(
        JNIEnv *env,
        jclass clazz,
        jobject effect,
        jint command_code,
        jbyteArray command,
        jbyteArray reply) {
    (void) clazz;
    if (effect == NULL || command == NULL || reply == NULL || audio_effect_command == NULL) {
        return ERROR_INVALID_OPERATION;
    }
    jint result = (*env)->CallIntMethod(
            env, effect, audio_effect_command, command_code, command, reply);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return ERROR_INVALID_OPERATION;
    }
    return result;
}
