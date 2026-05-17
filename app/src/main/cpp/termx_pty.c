/**
 * termx_pty.c - Native PTY implementation for TermX terminal emulator.
 *
 * This JNI native library provides true pseudo-terminal (PTY) support on Android,
 * enabling proper terminal emulation with job control, signal handling, and TTY
 * line discipline — features impossible with simple Runtime.exec().
 *
 * Key features:
 *   - forkpty() based PTY creation with proper /dev/ptmx handling
 *   - Session leader assignment via setsid()
 *   - TTY window size updates (SIGWINCH equivalent via ioctl TIOCSWINSZ)
 *   - Non-blocking I/O with proper signal handling (SIGHUP, SIGTERM, etc.)
 *   - Process group management for Ctrl+C/Z signal delivery
 *   - UTF-8 pass-through support
 *
 * Architecture:
 *   Parent process (Android app)
 *     -> forkpty() creates PTY master/slave pair
 *     -> Child process: setsid(), execvp() shell
 *     -> Parent reads/writes PTY master fd
 *     -> JNI callbacks feed data to TerminalEmulator
 */

#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <sys/select.h>
#include <termios.h>
#include <pty.h>
#include <linux/ioctl.h>

#define TAG "TermX-PTY"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* Terminal defaults */
#define DEFAULT_ROWS 24
#define DEFAULT_COLS 80

/**
 * PTY process structure — holds all state for a single terminal session.
 * Allocated per-session and passed as a jlong handle to Kotlin.
 */
typedef struct {
    int master_fd;       /* PTY master side (app reads/writes here) */
    pid_t child_pid;     /* PID of the forked shell process */
    int rows;            /* Current terminal rows */
    int cols;            /* Current terminal columns */
    int exited;          /* Non-zero if child has exited */
    int exit_code;       /* Exit status of child process */
} PtyProcess;

/* ---- Helper Functions ---- */

/**
 * Set a file descriptor to non-blocking mode.
 * Required for the PTY master fd so reads don't block the UI thread.
 */
static int set_nonblocking(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags == -1) return -1;
    return fcntl(fd, F_SETFL, flags | O_NONBLOCK);
}

/**
 * Set up the child process's slave PTY as a proper controlling terminal.
 * Called inside the child after forkpty().
 */
static void setup_child_pty(int slave_fd, int rows, int cols) {
    /* Create a new session */
    setsid();

    /* Set the slave as the controlling terminal */
    if (ioctl(slave_fd, TIOCSCTTY, 0) == -1) {
        LOGW("TIOCSCTTY failed: %s", strerror(errno));
    }

    /* Set initial window size */
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = rows;
    ws.ws_col = cols;
    ws.ws_xpixel = cols * 8;
    ws.ws_ypixel = rows * 16;
    if (ioctl(slave_fd, TIOCSWINSZ, &ws) == -1) {
        LOGW("TIOCSWINSZ failed: %s", strerror(errno));
    }

    /* Configure terminal attributes */
    struct termios tios;
    if (tcgetattr(slave_fd, &tios) == 0) {
        /* Input flags — enable BREAK, CR->NL, ignore parity */
        tios.c_iflag = ICRNL | BRKINT;

        /* Output flags — map NL to CR-NL (for terminals) */
        tios.c_oflag = OPOST | ONLCR;

        /* Control flags — 8-bit, enable receiver */
        tios.c_cflag = CS8 | CREAD;

        /* Local flags — canonical mode OFF (raw), signal handling ON */
        tios.c_lflag = ISIG | ECHO | ECHOE | ECHOK | ECHOCTL | ECHOKE;

        /* Control characters */
        tios.c_cc[VINTR] = 003;      /* Ctrl-C */
        tios.c_cc[VQUIT] = 034;      /* Ctrl-\ */
        tios.c_cc[VERASE] = 0177;    /* DEL */
        tios.c_cc[VKILL] = 025;      /* Ctrl-U */
        tios.c_cc[VEOF] = 004;       /* Ctrl-D */
        tios.c_cc[VSTOP] = 023;      /* Ctrl-S */
        tios.c_cc[VSTART] = 021;     /* Ctrl-Q */
        tios.c_cc[VSUSP] = 032;      /* Ctrl-Z */
        tios.c_cc[VLNEXT] = 026;     /* Ctrl-V */
        tios.c_cc[VWERASE] = 027;    /* Ctrl-W */

        tcsetattr(slave_fd, TCSANOW, &tios);
    }

    /* Redirect stdin/stdout/stderr to the slave PTY */
    dup2(slave_fd, STDIN_FILENO);
    dup2(slave_fd, STDOUT_FILENO);
    dup2(slave_fd, STDERR_FILENO);

    /* Close the original slave fd if it's not one of 0,1,2 */
    if (slave_fd > 2) close(slave_fd);
}

/* ---- JNI Functions ---- */

/**
 * nativeCreatePty() — Create a new PTY and fork a shell process.
 *
 * @param shellPath  Absolute path to the shell binary (e.g., /system/bin/sh)
 * @param cwd        Working directory for the shell
 * @param envArray   Array of "KEY=VALUE" environment strings
 * @param rows       Initial terminal rows
 * @param cols       Initial terminal columns
 * @return           jlong handle (pointer to PtyProcess), or 0 on failure
 */
JNIEXPORT jlong JNICALL
Java_com_termx_app_terminal_JniPty_nativeCreatePty(
    JNIEnv *env, jobject thiz,
    jstring shellPath, jstring cwd,
    jobjectArray envArray,
    jint rows, jint cols
) {
    const char *shell = (*env)->GetStringUTFChars(env, shellPath, NULL);
    const char *dir = (*env)->GetStringUTFChars(env, cwd, NULL);

    if (shell == NULL || dir == NULL) {
        LOGE("Invalid shell or cwd parameter");
        if (shell) (*env)->ReleaseStringUTFChars(env, shellPath, shell);
        if (dir) (*env)->ReleaseStringUTFChars(env, cwd, dir);
        return 0;
    }

    /* Allocate PTY process struct */
    PtyProcess *pty = (PtyProcess *)calloc(1, sizeof(PtyProcess));
    if (pty == NULL) {
        LOGE("Failed to allocate PtyProcess");
        (*env)->ReleaseStringUTFChars(env, shellPath, shell);
        (*env)->ReleaseStringUTFChars(env, cwd, dir);
        return 0;
    }

    pty->rows = rows > 0 ? rows : DEFAULT_ROWS;
    pty->cols = cols > 0 ? cols : DEFAULT_COLS;
    pty->exited = 0;
    pty->exit_code = -1;

    int master_fd = -1;
    pid_t pid = -1;

    /* Build environment array */
    int envCount = (*env)->GetArrayLength(env, envArray);
    char **envp = NULL;
    if (envCount > 0) {
        envp = (char **)malloc((envCount + 1) * sizeof(char *));
        if (envp == NULL) {
            LOGE("Failed to allocate env array");
            free(pty);
            (*env)->ReleaseStringUTFChars(env, shellPath, shell);
            (*env)->ReleaseStringUTFChars(env, cwd, dir);
            return 0;
        }
        for (int i = 0; i < envCount; i++) {
            jstring envStr = (jstring)(*env)->GetObjectArrayElement(env, envArray, i);
            const char *envCStr = (*env)->GetStringUTFChars(env, envStr, NULL);
            envp[i] = strdup(envCStr);
            (*env)->ReleaseStringUTFChars(env, envStr, envCStr);
            (*env)->DeleteLocalRef(env, envStr);
        }
        envp[envCount] = NULL;
    }

    /* Fork with PTY using forkpty() */
    pid = forkpty(&master_fd, NULL, NULL, NULL);

    if (pid < 0) {
        /* Fork failed */
        LOGE("forkpty() failed: %s", strerror(errno));
        if (envp) {
            for (int i = 0; i < envCount; i++) free(envp[i]);
            free(envp);
        }
        free(pty);
        (*env)->ReleaseStringUTFChars(env, shellPath, shell);
        (*env)->ReleaseStringUTFChars(env, cwd, dir);
        return 0;
    }

    if (pid == 0) {
        /* ---- CHILD PROCESS ---- */
        setup_child_pty(master_fd, pty->rows, pty->cols);

        /* Ensure working directory exists before chdir */
        struct stat st;
        if (stat(dir, &st) != 0) {
            /* Directory doesn't exist, try to create it */
            /* Create parent directories recursively */
            char path[512];
            strncpy(path, dir, sizeof(path) - 1);
            path[sizeof(path) - 1] = '\0';
            for (char *p = path + 1; *p; p++) {
                if (*p == '/') {
                    *p = '\0';
                    mkdir(path, 0755);
                    *p = '/';
                }
            }
            mkdir(dir, 0755);
        }

        /* Change working directory */
        if (chdir(dir) != 0) {
            /* Fallback to /tmp if chdir fails */
            LOGW("chdir(%s) failed: %s, falling back to /tmp", dir, strerror(errno));
            chdir("/tmp");
        }

        /* Close all non-standard file descriptors */
        for (int fd = 3; fd < 256; fd++) close(fd);

        /* Execute the shell using execve for proper environment passing */
        if (envp != NULL) {
            char *argv[] = { (char *)shell, NULL };
            execve(shell, argv, envp);
        } else {
            char *argv[] = { (char *)shell, NULL };
            execvp(shell, argv);
        }

        /* If exec returns, it failed */
        LOGE("exec(%s) failed: %s", shell, strerror(errno));
        _exit(127);
    }

    /* ---- PARENT PROCESS ---- */

    /* Set master fd to non-blocking */
    if (set_nonblocking(master_fd) != 0) {
        LOGW("Failed to set master fd non-blocking: %s", strerror(errno));
    }

    /* Clean up env strings */
    if (envp) {
        for (int i = 0; i < envCount; i++) free(envp[i]);
        free(envp);
    }

    (*env)->ReleaseStringUTFChars(env, shellPath, shell);
    (*env)->ReleaseStringUTFChars(env, cwd, dir);

    pty->master_fd = master_fd;
    pty->child_pid = pid;

    LOGI("PTY created: pid=%d, master_fd=%d, size=%dx%d", pid, master_fd, pty->cols, pty->rows);

    return (jlong)pty;
}

/**
 * nativeRead() — Read available data from the PTY master.
 *
 * @param handle   PtyProcess handle
 * @param bufSize  Maximum bytes to read
 * @return         ByteArray of read data, or null if nothing available / EOF
 */
JNIEXPORT jbyteArray JNICALL
Java_com_termx_app_terminal_JniPty_nativeRead(
    JNIEnv *env, jobject thiz,
    jlong handle, jint bufSize
) {
    PtyProcess *pty = (PtyProcess *)handle;
    if (pty == NULL || pty->exited) return NULL;

    char *buf = (char *)malloc(bufSize);
    if (buf == NULL) return NULL;

    ssize_t n = read(pty->master_fd, buf, bufSize);

    if (n > 0) {
        jbyteArray result = (*env)->NewByteArray(env, (jsize)n);
        if (result != NULL) {
            (*env)->SetByteArrayRegion(env, result, 0, (jsize)n, (jbyte *)buf);
        }
        free(buf);
        return result;
    }

    free(buf);

    if (n == 0) {
        /* EOF */
        return NULL;
    }

    /* n < 0 */
    if (errno == EAGAIN || errno == EWOULDBLOCK) {
        /* No data available right now — normal for non-blocking */
        return NULL;
    }

    /* Real error */
    LOGW("PTY read error: %s", strerror(errno));
    return NULL;
}

/**
 * nativeWrite() — Write data to the PTY master (sent to child's stdin).
 *
 * @param handle   PtyProcess handle
 * @param data     Byte array of data to write
 * @param offset   Offset into data array
 * @param length   Number of bytes to write
 * @return         Number of bytes actually written, or -1 on error
 */
JNIEXPORT jint JNICALL
Java_com_termx_app_terminal_JniPty_nativeWrite(
    JNIEnv *env, jobject thiz,
    jlong handle, jbyteArray data, jint offset, jint length
) {
    PtyProcess *pty = (PtyProcess *)handle;
    if (pty == NULL || pty->exited) return -1;

    jbyte *buf = (*env)->GetByteArrayElements(env, data, NULL);
    if (buf == NULL) return -1;

    ssize_t written = write(pty->master_fd, buf + offset, length);

    (*env)->ReleaseByteArrayElements(env, data, buf, JNI_ABORT);

    if (written < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) return 0;
        LOGW("PTY write error: %s", strerror(errno));
        return -1;
    }

    return (jint)written;
}

/**
 * nativeResize() — Update the PTY window size.
 * Sends TIOCSWINSZ ioctl which generates SIGWINCH in the child's foreground process group.
 *
 * @param handle  PtyProcess handle
 * @param rows    New row count
 * @param cols    New column count
 */
JNIEXPORT void JNICALL
Java_com_termx_app_terminal_JniPty_nativeResize(
    JNIEnv *env, jobject thiz,
    jlong handle, jint rows, jint cols
) {
    PtyProcess *pty = (PtyProcess *)handle;
    if (pty == NULL) return;

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)rows;
    ws.ws_col = (unsigned short)cols;
    ws.ws_xpixel = (unsigned short)(cols * 8);
    ws.ws_ypixel = (unsigned short)(rows * 16);

    if (ioctl(pty->master_fd, TIOCSWINSZ, &ws) == -1) {
        LOGW("TIOCSWINSZ failed: %s", strerror(errno));
    } else {
        pty->rows = rows;
        pty->cols = cols;
    }
}

/**
 * nativeGetChildPid() — Get the PID of the child shell process.
 */
JNIEXPORT jint JNICALL
Java_com_termx_app_terminal_JniPty_nativeGetChildPid(
    JNIEnv *env, jobject thiz, jlong handle
) {
    PtyProcess *pty = (PtyProcess *)handle;
    if (pty == NULL) return -1;
    return (jint)pty->child_pid;
}

/**
 * nativeIsChildAlive() — Check if the child process is still running.
 * Uses kill(pid, 0) which doesn't send a signal, just checks existence.
 */
JNIEXPORT jboolean JNICALL
Java_com_termx_app_terminal_JniPty_nativeIsChildAlive(
    JNIEnv *env, jobject thiz, jlong handle
) {
    PtyProcess *pty = (PtyProcess *)handle;
    if (pty == NULL || pty->exited) return JNI_FALSE;

    if (kill(pty->child_pid, 0) == 0) {
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

/**
 * nativeSendSignal() — Send a signal to the child process.
 * Used for Ctrl+C (SIGINT), Ctrl+Z (SIGTSTP), etc.
 *
 * @param handle  PtyProcess handle
 * @param signal  Signal number (e.g., SIGINT=2, SIGTERM=15, SIGKILL=9)
 */
JNIEXPORT void JNICALL
Java_com_termx_app_terminal_JniPty_nativeSendSignal(
    JNIEnv *env, jobject thiz, jlong handle, jint signal
) {
    PtyProcess *pty = (PtyProcess *)handle;
    if (pty == NULL) return;

    /* Send signal to the child's foreground process group */
    pid_t target = -pty->child_pid; /* Negative PID = process group */
    if (kill(target, signal) == -1) {
        /* If process group kill fails, try the process itself */
        if (kill(pty->child_pid, signal) == -1) {
            LOGW("kill(%d, %d) failed: %s", pty->child_pid, signal, strerror(errno));
        }
    }
}

/**
 * nativeWaitForExit() — Wait for the child process to exit (non-blocking check).
 * Uses waitpid with WNOHANG so it doesn't block.
 *
 * @param handle  PtyProcess handle
 * @return        Exit code if exited, -1 if still running, -2 on error
 */
JNIEXPORT jint JNICALL
Java_com_termx_app_terminal_JniPty_nativeWaitForExit(
    JNIEnv *env, jobject thiz, jlong handle
) {
    PtyProcess *pty = (PtyProcess *)handle;
    if (pty == NULL) return -2;

    if (pty->exited) return pty->exit_code;

    int status;
    pid_t result = waitpid(pty->child_pid, &status, WNOHANG);

    if (result == 0) {
        /* Still running */
        return -1;
    } else if (result == pty->child_pid) {
        pty->exited = 1;
        if (WIFEXITED(status)) {
            pty->exit_code = WEXITSTATUS(status);
        } else if (WIFSIGNALED(status)) {
            pty->exit_code = -WTERMSIG(status);
        } else {
            pty->exit_code = -1;
        }
        LOGI("Child %d exited with code %d", pty->child_pid, pty->exit_code);
        return pty->exit_code;
    }

    return -2;
}

/**
 * nativeClose() — Close the PTY and kill the child process.
 * Sends SIGHUP then SIGTERM, waits briefly, then SIGKILL if needed.
 */
JNIEXPORT void JNICALL
Java_com_termx_app_terminal_JniPty_nativeClose(
    JNIEnv *env, jobject thiz, jlong handle
) {
    PtyProcess *pty = (PtyProcess *)handle;
    if (pty == NULL) return;

    LOGI("Closing PTY: pid=%d, master_fd=%d", pty->child_pid, pty->master_fd);

    /* Close master fd first — this causes SIGHUP in the child's session */
    if (pty->master_fd >= 0) {
        close(pty->master_fd);
        pty->master_fd = -1;
    }

    /* Send SIGTERM as a fallback */
    if (pty->child_pid > 0 && !pty->exited) {
        kill(-pty->child_pid, SIGHUP);
        kill(-pty->child_pid, SIGTERM);

        /* Brief wait for graceful exit */
        usleep(100000); /* 100ms */

        int status;
        pid_t result = waitpid(pty->child_pid, &status, WNOHANG);
        if (result == 0) {
            /* Still running — SIGKILL */
            kill(pty->child_pid, SIGKILL);
            waitpid(pty->child_pid, &status, 0);
        }
        pty->exited = 1;
    }

    free(pty);
}

/**
 * nativeSetFd() — Get the master fd for use with select()/poll().
 * Allows the Kotlin side to efficiently wait for data.
 */
JNIEXPORT jint JNICALL
Java_com_termx_app_terminal_JniPty_nativeGetMasterFd(
    JNIEnv *env, jobject thiz, jlong handle
) {
    PtyProcess *pty = (PtyProcess *)handle;
    if (pty == NULL) return -1;
    return pty->master_fd;
}
