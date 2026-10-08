/*
 * TermDeb ADB bridge - PTY helper for the Java server (libadbpty.so).
 *
 * Mirrors the proven semantics of terminal-emulator's termux.c: open
 * /dev/ptmx, grantpt/unlockpt, fork, child becomes session leader with the
 * slave as controlling terminal, execve. Reduced to what the ADB bridge
 * needs (no terminal-emulator callback plumbing).
 */
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

/* Return {master_fd, pid} or {-errno, -errno} on failure. */
static jintArray make_result(JNIEnv* env, int master, int pid) {
    jintArray arr = (*env)->NewIntArray(env, 2);
    if (arr == NULL) return NULL;
    jint vals[2];
    vals[0] = master;
    vals[1] = pid;
    (*env)->SetIntArrayRegion(env, arr, 0, 2, vals);
    return arr;
}

static char** copy_string_array(JNIEnv* env, jobjectArray array, int count) {
    char** out = (char**)calloc((size_t)count + 1, sizeof(char*));
    if (out == NULL) return NULL;
    for (int i = 0; i < count; i++) {
        jstring js = (jstring)(*env)->GetObjectArrayElement(env, array, i);
        const char* utf = js ? (*env)->GetStringUTFChars(env, js, NULL) : "";
        out[i] = strdup(utf ? utf : "");
        if (js) (*env)->ReleaseStringUTFChars(env, js, utf);
        (*env)->DeleteLocalRef(env, js);
        if (out[i] == NULL) {
            for (int k = 0; k < i; k++) free(out[k]);
            free(out);
            return NULL;
        }
    }
    return out;
}

static void free_string_array(char** array) {
    if (array == NULL) return;
    for (int i = 0; array[i]; i++) free(array[i]);
    free(array);
}

JNIEXPORT jintArray JNICALL
Java_com_termux_app_adb_AdbPty_nativeForkPty(JNIEnv* env, jclass clazz,
                                             jobjectArray cmd,
                                             jobjectArray envVars,
                                             jint rows, jint cols) {
    (void)clazz;
    if (cmd == NULL) return make_result(env, -EINVAL, -EINVAL);

    int argc = (*env)->GetArrayLength(env, cmd);
    int envc = envVars ? (*env)->GetArrayLength(env, envVars) : 0;
    char** cargv = copy_string_array(env, cmd, argc);
    char** cenv = envc > 0 ? copy_string_array(env, envVars, envc) : NULL;
    if (cargv == NULL || (envc > 0 && cenv == NULL)) {
        free_string_array(cargv);
        free_string_array(cenv);
        return make_result(env, -ENOMEM, -ENOMEM);
    }

    int master = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (master < 0) {
        int err = -errno;
        free_string_array(cargv);
        free_string_array(cenv);
        return make_result(env, err, err);
    }
    if (grantpt(master) < 0 || unlockpt(master) < 0) {
        int err = -errno;
        close(master);
        free_string_array(cargv);
        free_string_array(cenv);
        return make_result(env, err, err);
    }
    char slaveName[128];
    if (ptsname_r(master, slaveName, sizeof(slaveName)) != 0) {
        int err = -errno;
        close(master);
        free_string_array(cargv);
        free_string_array(cenv);
        return make_result(env, err, err);
    }

    pid_t pid = fork();
    if (pid < 0) {
        int err = -errno;
        close(master);
        free_string_array(cargv);
        free_string_array(cenv);
        return make_result(env, err, err);
    }

    if (pid == 0) {
        /* Child. */
        close(master);
        int slave = open(slaveName, O_RDWR);
        if (slave < 0) _exit(127);
        setsid();
        ioctl(slave, TIOCSCTTY, 0);

        struct winsize ws;
        memset(&ws, 0, sizeof(ws));
        ws.ws_row = (unsigned short)(rows > 0 ? rows : 24);
        ws.ws_col = (unsigned short)(cols > 0 ? cols : 80);
        ioctl(slave, TIOCSWINSZ, &ws);

        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);

        signal(SIGHUP, SIG_DFL);
        signal(SIGINT, SIG_DFL);
        signal(SIGQUIT, SIG_DFL);
        signal(SIGPIPE, SIG_DFL);
        signal(SIGCHLD, SIG_DFL);
        signal(SIGWINCH, SIG_DFL);

        execve(cargv[0], cargv, cenv ? cenv : NULL);
        _exit(127);
    }

    /* Parent. */
    free_string_array(cargv);
    free_string_array(cenv);
    return make_result(env, master, pid);
}

JNIEXPORT jint JNICALL
Java_com_termux_app_adb_AdbPty_nativeSetWindowSize(JNIEnv* env, jclass clazz,
                                                   jint masterFd, jint rows, jint cols) {
    (void)env;
    (void)clazz;
    if (masterFd < 0) return -EBADF;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)(rows > 0 ? rows : 24);
    ws.ws_col = (unsigned short)(cols > 0 ? cols : 80);
    if (ioctl(masterFd, TIOCSWINSZ, &ws) < 0) return -errno;
    return 0;
}

/* Returns the raw waitpid status, or -1 if the pid is not a child. */
JNIEXPORT jint JNICALL
Java_com_termux_app_adb_AdbPty_nativeWaitFor(JNIEnv* env, jclass clazz, jint pid) {
    (void)env;
    (void)clazz;
    int status = 0;
    if (waitpid((pid_t)pid, &status, 0) < 0) return -1;
    return status;
}

/* read(fd, buf, len): bytes read, 0 on EOF, -errno on error. */
JNIEXPORT jint JNICALL
Java_com_termux_app_adb_AdbPty_nativeReadFd(JNIEnv* env, jclass clazz, jint fd,
                                            jbyteArray buf, jint len) {
    (void)clazz;
    if (len <= 0) return 0;
    jbyte* p = (*env)->GetByteArrayElements(env, buf, NULL);
    if (p == NULL) return -ENOMEM;
    ssize_t n;
    do {
        n = read((int)fd, p, (size_t)len);
    } while (n < 0 && errno == EINTR);
    (*env)->ReleaseByteArrayElements(env, buf, p, 0);
    if (n < 0) return (jint)-errno;
    return (jint)n;
}

/* write(fd, buf, len): bytes written, or -errno. */
JNIEXPORT jint JNICALL
Java_com_termux_app_adb_AdbPty_nativeWriteFd(JNIEnv* env, jclass clazz, jint fd,
                                             jbyteArray buf, jint len) {
    (void)clazz;
    if (len <= 0) return 0;
    jbyte* p = (*env)->GetByteArrayElements(env, buf, NULL);
    if (p == NULL) return -ENOMEM;
    ssize_t n;
    do {
        n = write((int)fd, p, (size_t)len);
    } while (n < 0 && errno == EINTR);
    (*env)->ReleaseByteArrayElements(env, buf, p, JNI_ABORT);
    if (n < 0) return (jint)-errno;
    return (jint)n;
}

JNIEXPORT jint JNICALL
Java_com_termux_app_adb_AdbPty_nativeCloseFd(JNIEnv* env, jclass clazz, jint fd) {
    (void)env;
    (void)clazz;
    if (close((int)fd) < 0) return (jint)-errno;
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_termux_app_adb_AdbPty_nativeKillPid(JNIEnv* env, jclass clazz, jint pid) {
    (void)env;
    (void)clazz;
    if (kill((pid_t)pid, SIGKILL) < 0) return (jint)-errno;
    return 0;
}
