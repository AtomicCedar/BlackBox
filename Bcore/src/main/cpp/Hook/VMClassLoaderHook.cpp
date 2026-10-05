//
// Created by Milk on 2021/5/5.
//

//
// Created by Milk on 5/5/21.
//

#include <cstring>
#include "VMClassLoaderHook.h"
#import "JniHook/JniHook.h"
static bool hideXposedClass = false;

// Xposed 框架类名特征（点分与斜杠双形式都查）。
// 注意 org.lsposed 只精确到 libxposed API 包，不泛匹配——容器自身依赖
// org.lsposed.hiddenapibypass（unseal），泛匹配会误伤自己的类加载。
static bool isXposedClassName(const char *nameC) {
    return strstr(nameC, "de/robv/android/xposed/") != nullptr ||
           strstr(nameC, "de.robv.android.xposed") != nullptr ||
           strstr(nameC, "me/weishu/epic") != nullptr ||
           strstr(nameC, "me/weishu/exposed") != nullptr ||
           strstr(nameC, "me.weishu.epic") != nullptr ||
           strstr(nameC, "me.weishu.exposed") != nullptr ||
           strstr(nameC, "org/lsposed/libxposed/") != nullptr ||
           strstr(nameC, "org.lsposed.libxposed") != nullptr;
}

HOOK_JNI(jobject, findLoadedClass, JNIEnv *env, jobject obj, jobject class_loader, jstring name) {
    const char * nameC = env->GetStringUTFChars(name, JNI_FALSE);
//     ALOGD("findLoadedClass: %s", nameC);
    if (hideXposedClass) {
        if (isXposedClassName(nameC)) {
            env->ReleaseStringUTFChars(name, nameC);
            return nullptr;
        }
    }
    jobject result = orig_findLoadedClass(env, obj, class_loader, name);
    env->ReleaseStringUTFChars(name, nameC);
    return result;
}

// Class.forName(String, boolean, ClassLoader)：加载新类也拦（findLoadedClass 只挡已加载的）
HOOK_JNI(jclass, Class_forName, JNIEnv *env, jclass clazz, jstring name, jboolean initialize, jobject loader) {
    const char *nameC = env->GetStringUTFChars(name, JNI_FALSE);
    if (hideXposedClass && isXposedClassName(nameC)) {
        env->ReleaseStringUTFChars(name, nameC);
        jclass cnfe = env->FindClass("java/lang/ClassNotFoundException");
        if (cnfe != nullptr) {
            env->ThrowNew(cnfe, nameC);
        }
        return nullptr;
    }
    jclass result = orig_Class_forName(env, clazz, name, initialize, loader);
    env->ReleaseStringUTFChars(name, nameC);
    return result;
}

void VMClassLoaderHook::init(JNIEnv *env) {
    const char *className = "java/lang/VMClassLoader";
    JniHook::HookJniFun(env, className, "findLoadedClass", "(Ljava/lang/ClassLoader;Ljava/lang/String;)Ljava/lang/Class;",
                        (void *) new_findLoadedClass,
                        (void **) (&orig_findLoadedClass), true);
    const char *classClassName = "java/lang/Class";
    JniHook::HookJniFun(env, classClassName, "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;",
                        (void *) new_Class_forName,
                        (void **) (&orig_Class_forName), true);
}

void VMClassLoaderHook::hideXposed() {
    hideXposedClass = true;
}
