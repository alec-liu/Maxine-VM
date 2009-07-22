/*
 * Copyright (c) 2007 Sun Microsystems, Inc.  All rights reserved.
 *
 * Sun Microsystems, Inc. has intellectual property rights relating to technology embodied in the product
 * that is described in this document. In particular, and without limitation, these intellectual property
 * rights may include one or more of the U.S. patents listed at http://www.sun.com/patents and one or
 * more additional patents or pending patent applications in the U.S. and in other countries.
 *
 * U.S. Government Rights - Commercial software. Government users are subject to the Sun
 * Microsystems, Inc. standard license agreement and applicable provisions of the FAR and its
 * supplements.
 *
 * Use is subject to license terms. Sun, Sun Microsystems, the Sun logo, Java and Solaris are trademarks or
 * registered trademarks of Sun Microsystems, Inc. in the U.S. and other countries. All SPARC trademarks
 * are used under license and are trademarks or registered trademarks of SPARC International, Inc. in the
 * U.S. and other countries.
 *
 * UNIX is a registered trademark in the U.S. and other countries, exclusively licensed through X/Open
 * Company, Ltd.
 */
/**
 * JNI test code for the JVM_* native interface.
 * This JNI code supports unit testing of the JVM interface through the JavaTester.
 */

#include "os.h"

#include <stdlib.h>
#include <string.h>

#include "jni.h"

JNIEXPORT jobjectArray JNICALL
JVM_GetClassContext(JNIEnv *env);

JNIEXPORT jboolean JNICALL
JVM_IsNaN(JNIEnv *env, jdouble d);

JNIEXPORT jlong JNICALL
JVM_MaxMemory();

JNIEXPORT jlong JNICALL
JVM_TotalMemory();

JNIEXPORT jlong JNICALL
JVM_FreeMemory();

JNIEXPORT void JNICALL
JVM_ArrayCopy(JNIEnv *env, jclass ignored, jobject src, jint src_pos,
               jobject dst, jint dst_pos, jint length);

JNIEXPORT jobject JNICALL
Java_test_jvmni_JVM_1GetClassContext01_call(JNIEnv *env, jclass c)
{
    return JVM_GetClassContext(env);
}

JNIEXPORT jobject JNICALL
Java_test_jvmni_JVM_1GetClassContext02_downCall1(JNIEnv *env, jclass c)
{
    jclass jClass;
    jmethodID jMethod;
    char *className = "test/jvmni/JVM_GetClassContext02";
    char *methodName = "upCall1";
    char *signature = "()[Ljava/lang/Class;";
    jClass = (*env)->FindClass(env, className);
    if (jClass == NULL) {
        return NULL;
    }
    jMethod = (*env)->GetStaticMethodID(env, jClass, methodName, signature);
    if (jMethod == NULL) {
        return NULL;
    }
    return (*env)->CallStaticObjectMethod(env, jClass, jMethod);
}

JNIEXPORT jobject JNICALL
Java_test_jvmni_JVM_1GetClassContext02_downCall2(JNIEnv *env, jclass c)
{
    return JVM_GetClassContext(env);
}


JNIEXPORT jboolean JNICALL
Java_test_jvmni_JVM_1IsNaN01_call(JNIEnv *env, jdouble d)
{
	return JVM_IsNaN(env,d);
}

JNIEXPORT jlong JNICALL
Java_test_jvmni_JVM_1GetMaxMemory01_call(JNIEnv *env)
{
	return JVM_MaxMemory();
}

JNIEXPORT jlong JNICALL
Java_test_jvmni_JVM_1GetTotalMemory01_call(JNIEnv *env)
{
	return JVM_TotalMemory();
}

JNIEXPORT jlong JNICALL
Java_test_jvmni_JVM_1GetFreeMemory01_call(JNIEnv *env)
{
	return JVM_FreeMemory();
}

JNIEXPORT void JNICALL
Java_test_jvmni_JVM_1ArrayCopy01_call(JNIEnv *env, jclass jc, jobject src, jint src_pos, jobject dest, jint dest_pos, jint len)
{
	JVM_ArrayCopy(env, jc, src, src_pos, dest, dest_pos, len);
}

