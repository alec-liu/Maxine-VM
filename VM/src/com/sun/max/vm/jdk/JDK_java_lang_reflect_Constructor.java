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
package com.sun.max.vm.jdk;

import static com.sun.c1x.bytecode.Bytecodes.*;

import java.lang.annotation.*;
import java.lang.reflect.*;
import java.util.*;

import sun.reflect.annotation.*;

import com.sun.c1x.bytecode.*;
import com.sun.max.annotate.*;
import com.sun.max.vm.actor.member.*;

/**
 * Method substitutions for {@link Constructor}.
 *
 * @author Mick Jordan
 */
@METHOD_SUBSTITUTIONS(Constructor.class)
final class JDK_java_lang_reflect_Constructor {

    private JDK_java_lang_reflect_Constructor() {
    }

    /**
     * An unsafe cast from this to {@link Constructor}.
     */
    @INTRINSIC(UNSAFE_CAST)
    private native Constructor thisConstructor();

    /**
     * Gets the method actor corresponding to this constructor.
     * @return the method actor for this constructor
     */
    @INLINE
    private MethodActor thisMethodActor() {
        return MethodActor.fromJavaConstructor(thisConstructor());
    }

    /**
     * Gets the declared annotations of this constructor.
     * @return a map from this class to its declared annotations.
     */
    @SUBSTITUTE
    private synchronized Map<Class, Annotation> declaredAnnotations() {
        final MethodActor methodActor = thisMethodActor();
        // in java.lang.reflect.Method.declaredAnnotations, the result is cached. Not sure how to do that using substitution.
        return AnnotationParser.parseAnnotations(methodActor.runtimeVisibleAnnotationsBytes(), new ConstantPoolAdapter(methodActor.holder().constantPool()), methodActor.holder().toJava());

    }

}
