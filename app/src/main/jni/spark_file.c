#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <android/log.h>

/*
 * 用 svc 指令直接陷内核读文件，不经过 libc 的 open/read，也就绕开
 * xhook 那类 PLT/GOT hook（它们只改 .got.plt 里的函数地址）。
 *
 * 只做一件事：把指定路径的整个文件读成 byte[] 交给 Kotlin 层解析。
 * 签名块 / ZIP / 清单的解析仍留在 Kotlin（纯字节操作，不碰文件 API）。
 */

#define LOG_TAG "SparkFile"
#define MAX_FILE_BYTES (64 * 1024 * 1024)

static inline long raw_syscall3(long number, long a1, long a2, long a3) {
#if defined(__aarch64__)
    register long x8 __asm__("x8") = number;
    register long x0 __asm__("x0") = a1;
    register long x1 __asm__("x1") = a2;
    register long x2 __asm__("x2") = a3;
    __asm__ volatile("svc #0"
                     : "+r"(x0)
                     : "r"(x8), "r"(x1), "r"(x2)
                     : "memory");
    return x0;
#else
    long ret;
    __asm__ volatile(
        "mov r7, %1\n"
        "mov r0, %2\n"
        "mov r1, %3\n"
        "mov r2, %4\n"
        "svc #0\n"
        "mov %0, r0\n"
        : "=r"(ret)
        : "r"(number), "r"(a1), "r"(a2), "r"(a3)
        : "r0", "r1", "r2", "r7", "memory");
    return ret;
#endif
}

static inline long raw_syscall4(long number, long a1, long a2, long a3, long a4) {
#if defined(__aarch64__)
    register long x8 __asm__("x8") = number;
    register long x0 __asm__("x0") = a1;
    register long x1 __asm__("x1") = a2;
    register long x2 __asm__("x2") = a3;
    register long x3 __asm__("x3") = a4;
    __asm__ volatile("svc #0"
                     : "+r"(x0)
                     : "r"(x8), "r"(x1), "r"(x2), "r"(x3)
                     : "memory");
    return x0;
#else
    long ret;
    __asm__ volatile(
        "mov r7, %1\n"
        "mov r0, %2\n"
        "mov r1, %3\n"
        "mov r2, %4\n"
        "mov r3, %5\n"
        "svc #0\n"
        "mov %0, r0\n"
        : "=r"(ret)
        : "r"(number), "r"(a1), "r"(a2), "r"(a3), "r"(a4)
        : "r0", "r1", "r2", "r3", "r7", "memory");
    return ret;
#endif
}

static const char *read_all_raw(const char *path, size_t *out_len) {
    long fd = raw_syscall4(__NR_openat, -100L, (long)path,
                           (long)(O_RDONLY | O_CLOEXEC), 0L);
    if (fd < 0) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "openat failed: %ld", fd);
        return NULL;
    }

    size_t cap = 64 * 1024;
    size_t len = 0;
    char *buf = (char *)malloc(cap);
    if (buf == NULL) {
        raw_syscall3(__NR_close, fd, 0, 0);
        return NULL;
    }

    for (;;) {
        if (len == cap) {
            if (cap >= MAX_FILE_BYTES) {
                __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "file too large");
                free(buf);
                raw_syscall3(__NR_close, fd, 0, 0);
                return NULL;
            }
            size_t next = cap * 2;
            char *grown = (char *)realloc(buf, next);
            if (grown == NULL) {
                free(buf);
                raw_syscall3(__NR_close, fd, 0, 0);
                return NULL;
            }
            buf = grown;
            cap = next;
        }
        long n = raw_syscall3(__NR_read, fd, (long)(buf + len), (long)(cap - len));
        if (n == 0) break;
        if (n < 0) {
            if (n == -EINTR) continue;
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "read failed: %ld", n);
            free(buf);
            raw_syscall3(__NR_close, fd, 0, 0);
            return NULL;
        }
        len += (size_t)n;
    }

    raw_syscall3(__NR_close, fd, 0, 0);
    *out_len = len;
    return buf;
}

/* 不导出 Java_包名_类名_方法名 符号：改成无意义的短名，由 JNI_OnLoad 动态注册映射。 */
static jbyteArray r0(JNIEnv *env, jclass clazz, jstring jpath) {
    (void)clazz;
    if (jpath == NULL) return NULL;

    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (path == NULL) return NULL;

    size_t len = 0;
    const char *data = read_all_raw(path, &len);
    (*env)->ReleaseStringUTFChars(env, jpath, path);

    if (data == NULL) return NULL;

    jbyteArray result = NULL;
    if (len <= (size_t)INT32_MAX) {
        result = (*env)->NewByteArray(env, (jsize)len);
        if (result != NULL && len > 0) {
            (*env)->SetByteArrayRegion(env, result, 0, (jsize)len, (const jbyte *)data);
        }
    }
    free((void *)data);
    return result;
}

/*
 * 动态注册：不导出 Java_包名_类名_方法名 符号，so 的导出表里只有 JNI_OnLoad。
 * 类名以字符串放在 .rodata（不是符号名），方法名 r0 —— 反编译 so 看不到
 * 「哪个 Java 方法对应哪段 native 代码」的直接符号关联。
 */
static JNINativeMethod g_methods[] = {
    {"r0", "(Ljava/lang/String;)[B", (void *)r0},
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;

    jclass cls = (*env)->FindClass(env, "com/worldmargin/autospark/core/NativeFileReader");
    if (cls == NULL) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "FindClass a/a failed");
        return JNI_ERR;
    }
    if ((*env)->RegisterNatives(env, cls, g_methods, 1) != JNI_OK) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "RegisterNatives failed");
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
