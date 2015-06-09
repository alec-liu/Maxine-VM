/*
 * Copyright (c) 2007, 2012, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.sun.max.vm.compiler;

import com.sun.cri.ci.CiStatistics;
import com.sun.max.annotate.HOSTED_ONLY;
import com.sun.max.annotate.RESET;
import com.sun.max.lang.ISA;
import com.sun.max.unsafe.Address;
import com.sun.max.unsafe.CodePointer;
import com.sun.max.unsafe.Pointer;
import com.sun.max.vm.Log;
import com.sun.max.vm.MaxineVM;
import com.sun.max.vm.MaxineVM.Phase;
import com.sun.max.vm.VMOptions;
import com.sun.max.vm.actor.Actor;
import com.sun.max.vm.actor.holder.Hub;
import com.sun.max.vm.actor.member.ClassMethodActor;
import com.sun.max.vm.code.CodeCacheMetricsPrinter;
import com.sun.max.vm.compiler.RuntimeCompiler.*;
import com.sun.max.vm.compiler.target.Compilation;
import com.sun.max.vm.compiler.target.Compilations;
import com.sun.max.vm.compiler.target.Safepoints;
import com.sun.max.vm.compiler.target.TargetMethod;
import com.sun.max.vm.compiler.target.amd64.AMD64TargetMethodUtil;
import com.sun.max.vm.compiler.target.arm.ARMTargetMethodUtil;
import com.sun.max.vm.heap.Heap;
import com.sun.max.vm.object.ObjectAccess;
import com.sun.max.vm.profile.MethodInstrumentation;
import com.sun.max.vm.profile.MethodProfile;
import com.sun.max.vm.runtime.FatalError;
import com.sun.max.vm.runtime.VMRegister;
import com.sun.max.vm.stack.RawStackFrameVisitor;
import com.sun.max.vm.stack.StackFrameCursor;
import com.sun.max.vm.stack.VmStackFrameWalker;
import com.sun.max.vm.thread.VmThread;
import com.sun.max.vm.ti.VMTI;

import java.util.*;

import static com.sun.max.platform.Platform.platform;
import static com.sun.max.vm.AbstractVMScheme.configValue;
import static com.sun.max.vm.MaxineVM.isHosted;
import static com.sun.max.vm.MaxineVM.vm;
import static com.sun.max.vm.VMOptions.addFieldOption;
import static com.sun.max.vm.VMOptions.verboseOption;
import static com.sun.max.vm.compiler.CallEntryPoint.*;
import static com.sun.max.vm.compiler.RuntimeCompiler.*;
import static com.sun.max.vm.compiler.target.Safepoints.DIRECT_CALL;
import static com.sun.max.vm.intrinsics.Infopoints.here;

/**
 * This class implements an adaptive compilation system with multiple compilers with different compilation time / code
 * quality tradeoffs. It encapsulates the necessary infrastructure for recording profiling data, selecting what and when
 * to recompile, etc.
 */
public class CompilationBroker {

    /**
     * The threshold at which a recompilation is triggered from the baseline compiler to the next level
     * of optimization. This is typically the number of invocations of the method.
     */
    private static int RCT = 5000;

    /**
     * A queue of pending compilations.
     */
    protected final LinkedList<Compilation> pending = new LinkedList<Compilation>();

    /**
     * The baseline compiler.
     */
    public final RuntimeCompiler baselineCompiler;

    /**
     * The optimizing compiler.
     */
    public final RuntimeCompiler optimizingCompiler;

    /**
     * Other compilers registered with {@link #addCompiler}.
     */
    private HashMap<String, RuntimeCompiler> altCompilers = new HashMap<String, RuntimeCompiler>();

    private static boolean opt;
    private static boolean GCOnRecompilation;
    private static boolean FailOverCompilation = true;
    private static boolean VMExtOpt;
    static int PrintCodeCacheMetrics;
    public static boolean OFFLINE = false;
    /* SIMULATEADAPTER needs to be set to true in any test harness where inlining might occur and we are compiled by C1X
    the number of nops is 4, movw(r12) movt(r12), add(r12,pc,r12), blx(r12) to account for the call to the adapter
     Is this also true for T1X compiled methods? */
    public static boolean SIMULATEADAPTER = false; // needed to be set for test harness when method calls occur

    static {
        addFieldOption("-X", "opt", CompilationBroker.class, "Select optimizing compiler whenever possible.");
        addFieldOption("-XX:", "RCT", CompilationBroker.class, "Set the recompilation threshold for methods. Use 0 to disable recompilation. (default: " + RCT + ").");
        addFieldOption("-XX:", "GCOnRecompilation", CompilationBroker.class, "Force GC before every re-compilation.");
        addFieldOption("-XX:", "FailOverCompilation", CompilationBroker.class, "Retry failed compilations with another compiler (if available).");
        addFieldOption("-XX:", "PrintCodeCacheMetrics", CompilationBroker.class, "Print code cache metrics (0 = disabled, 1 = summary, 2 = verbose).");
        addFieldOption("-XX:", "VMExtOpt", CompilationBroker.class, "Compile VM extensions with optimizing compiler (default: false");
        addFieldOption("-XX:", "AddCompiler", CompilationBroker.class, "Add a compiler, Name:Class");
    }

    @RESET
    static String CompileCommand;
    static {
        VMOptions.addFieldOption("-XX:", "CompileCommand", CompilationBroker.class,
            "Specify which compiler to use for methods matching given patterns. For example, " +
            "'-XX:CompileCommand=test.output:T1X,com.acme.util.Strings:Graal' specifies that " +
            "any method whose fully qualified name contains the substring 'test.output' " +
            "should be compiled with the compiler named 'T1X' and any method whose fully " +
            "qualified name contains 'com.acme.util.String' should be compiled with the 'Graal' " +
            "compiler. No checking is done to ensure that a named compiler exists.");
    }

    private LinkedHashMap<String, String> compileCommandMap;

    /**
     * Gets the name of the compiler to be used to
     * compile {@code cma} as specified by the {@code -XX:CompileCommand} VM option.
     *
     * @return {@code null} if no specific compiler was specified for {@code cma} by a {@code -XX:CompileCommand} VM option
     */
    public String compilerFor(ClassMethodActor cma) {
        if (CompileCommand == null) {
            return null;
        }
        // A race to parse the option and create the map is fine. The result will be identical
        // for both threads and so one result just becomes instant garbage.
        if (compileCommandMap == null) {
            LinkedHashMap<String, String> map = new LinkedHashMap<String, String>();
            String[] parts = CompileCommand.split(",");
            for (String part : parts) {
                int colon = part.indexOf(':');
                if (colon == -1 || colon == 0 || colon == part.length() - 1) {
                    Log.println("CompileCommand part does not match a <key>:<value> pattern: " + part);
                } else {
                    String key = part.substring(0, colon);
                    String value = part.substring(colon + 1);
                    map.put(key, value);
                    Log.println("CompileCommand key value " + key + " " + value);
                }
            }
            compileCommandMap = map;
        }
        String methodString = cma.toString();
        for (Map.Entry<String, String> e : compileCommandMap.entrySet()) {
            if (methodString.contains(e.getKey()) || "*".equals(e.getKey())) {
                Log.println("CompileCommand: matched " + e.getKey());
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * The default compiler to use.
     */
    private RuntimeCompiler defaultCompiler;

    private static final boolean BACKGROUND_COMPILATION = false;

    public boolean needsAdapters() {
        return baselineCompiler != null;
    }

    public boolean isDeoptSupported() {
        return baselineCompiler != null;
    }

    private static String AddCompiler;

    private static final String OPTIMIZING_COMPILER_PROPERTY = CompilationBroker.class.getSimpleName() + "." + optimizingCompilerOption.getName();
    private static final String BASELINE_COMPILER_PROPERTY = CompilationBroker.class.getSimpleName() + "." + baselineCompilerOption.getName();

    /**
     * Gets the class name of the optimizing compiler that will be configured when an instance of this scheme is instantiated.
     */
    @HOSTED_ONLY
    public static String optName() {
        return configValue(OPTIMIZING_COMPILER_PROPERTY, optimizingCompilerOption, aliases);
    }

    /**
     * Gets the class name of the baseline compiler that will be configured when an instance of this scheme is instantiated.
     */
    @HOSTED_ONLY
    public static String baselineName() {
        return configValue(BASELINE_COMPILER_PROPERTY, baselineCompilerOption, aliases);
    }

    /**
     * The name of the system property specifying a subclass of {@link CompilationBroker} that is
     * to be instantiated instead of {@link CompilationBroker} itself.
     */
    public static final String COMPILATION_BROKER_CLASS_PROPERTY_NAME = "max.CompilationBroker.class";

    public static CompilationBroker singleton;

    /**
     * Creates the single {@link CompilationBroker} instance to be used by the VM.
     * This factory-style instantiation allows a subclass of {@link CompilationBroker} to
     * be created instead.
     *
     * @see #COMPILATION_BROKER_CLASS_PROPERTY_NAME
     */
    @HOSTED_ONLY
    public static CompilationBroker create() {
        final String className = System.getProperty(COMPILATION_BROKER_CLASS_PROPERTY_NAME);
        if (className == null) {
            singleton = new CompilationBroker();
        } else {
            try {
                singleton =  (CompilationBroker) Class.forName(className).newInstance();
            } catch (Exception exception) {
                throw FatalError.unexpected("Error instantiating " + className, exception);
            }
        }
        return singleton;
    }

    /**
     * This constructor should only be called from {@link #create()} or a subclass of {@link CompilationBroker}.
     */
    @HOSTED_ONLY
    protected CompilationBroker() {
        assert optimizingCompilerOption.getValue() != null;
        String optName = optName();
        String baselineName = baselineName(); // these are of the form com.oracle.max.vm.ext.t1x.T1X/C1X
        // trying to use this to add another compiler
        optimizingCompiler = instantiateCompiler(optName);
        assert optimizingCompiler.nature() == Nature.OPT : optimizingCompiler + " is not an optimizing compiler";
        if (baselineName != null) {
            baselineCompiler = instantiateCompiler(baselineName);
            assert baselineCompiler.nature() == Nature.BASELINE : baselineCompiler + " is not a baseline compiler";
            assert baselineCompiler != optimizingCompiler;
            defaultCompiler = baselineCompiler;
        } else {
            baselineCompiler = null;
            defaultCompiler = optimizingCompiler;
        }
    }

    public static RuntimeCompiler instantiateCompiler(String name) {
        try {
            return (RuntimeCompiler) Class.forName(name).newInstance();
        } catch (Exception e) {
            throw FatalError.unexpected("Error instantiating compiler " + name, e);
        }
    }

    public static RuntimeCompiler addCompiler(String name, String className) {
        RuntimeCompiler compiler = instantiateCompiler(className);
        singleton.altCompilers.put(name, compiler);
        return compiler;
    }

    /**
     * Gets a string describing the compilation mode.
     *
     * @return a string suitable for inclusion in the output produced by the {@link sun.misc.Version -version} VM option
     */
    public String mode() {
        if (RCT != 0) {
            if (defaultCompiler == baselineCompiler) {
                return "mixed mode, baseline-compile first";
            }
            return "mixed mode, optimize first";
        }
        return "optimizing-only";
    }

    /**
     * Gets the set of system properties which are used to configure the compilers.
     */
    public Properties properties() {
        Properties props = new Properties();
        props.put(OPTIMIZING_COMPILER_PROPERTY, optimizingCompiler.getClass().getName());
        if (baselineCompiler != null) {
            props.put(BASELINE_COMPILER_PROPERTY, baselineCompiler.getClass().getName());
        }
        return props;
    }

    /**
     * This method initializes the adaptive compilation system, either while bootstrapping or
     * at VM startup time. This implementation may create daemon threads for background compilation.
     *
     * @param phase the phase of VM starting up.
     */
    public void initialize(MaxineVM.Phase phase) {
	Log.println("COMPILATION BROKER INITIALIZE");
        optimizingCompiler.initialize(phase);
        if (baselineCompiler != null) {
            baselineCompiler.initialize(phase);
        }

        if (phase == MaxineVM.Phase.HOSTED_COMPILING || phase == MaxineVM.Phase.STARTING) {
            if (AddCompiler != null) {
                String[] nameAndClass = AddCompiler.split(":");
                addCompiler(nameAndClass[0], nameAndClass[1]);
                AddCompiler = null;
            }
            if (altCompilers != null) {
                for (RuntimeCompiler altCompiler : altCompilers.values()) {
                    altCompiler.initialize(phase);
                }
            }
        }

        if (isHosted()) {
            if (BACKGROUND_COMPILATION) {
                // launch a compiler thread if background compilation is supported (currently no)
                final CompilationThread compilationThread = new CompilationThread();
                compilationThread.setDaemon(true);
                compilationThread.start();
            }
        } else if (phase == MaxineVM.Phase.STARTING) {
            if (opt) {
                defaultCompiler = optimizingCompiler;
            }

            if (RCT != 0 && baselineCompiler != null) {
                MethodInstrumentation.enable(RCT);
            }

            if (BACKGROUND_COMPILATION) {
                // launch a compiler thread if background compilation is supported (currently no)
                final CompilationThread compilationThread = new CompilationThread();
                compilationThread.setDaemon(true);
                compilationThread.start();
            }
        } else if (phase == Phase.RUNNING) {
            if (PrintCodeCacheMetrics != 0) {
                Runtime.getRuntime().addShutdownHook(new Thread("CodeCacheMetricsPrinter") {
                    @Override
                    public void run() {
                        new CodeCacheMetricsPrinter(CompilationBroker.PrintCodeCacheMetrics > 1).printTo(Log.out);
                    }
                });
            }
        }
    }

    /**
     * Default compilation, not for deopt.
     * @param cma
     * @param nature
     */
    public TargetMethod compile(ClassMethodActor cma, Nature nature) {
        return compile(cma, nature, false);
    }

    /**
     * Deopt compilation, if necessary.
     * The method is only recompiled if the current target method has been invalidated, which is the normal deopt case.
     * However, a VMTI handler may already have compiled the method before the deoptimzation step happened.
     * @param cma
     * @param force always compile iff {@code true}.
     */
    public TargetMethod compileForDeopt(ClassMethodActor cma) {
        TargetMethod tm = cma.currentTargetMethod();
        if (tm != null && tm.isBaseline() && tm.invalidated() == null) {
            return tm;
        }
        return compile(cma, Nature.BASELINE, true);
    }

    public TargetMethod compile(ClassMethodActor cma, Nature nature, boolean isDeopt) {
        try {
            return compile(cma, nature, isDeopt, false);
        } catch (Throwable t) {
            throw new RuntimeException("unexpected compilation failure: ", t);
        }
    }

    /**
     * Produces a target method for the specified method actor. If another thread is currently
     * compiling {@code cma}, then the result of that compilation is returned. Otherwise,
     * a new compilation is scheduled and its result is returned. Either way, this method
     * waits for the result of a compilation to return it.
     *
     * @param cma the method for which to make the target method
     * @param nature the specific type of target method required or {@code null} if any target method is acceptable
     * @param isDeopt if the compilation is for a deoptimzation
     * @param failFast don't try recompilation on failure, instead rethrow the exception
     * @return a newly compiled version of a {@code cma}
     * @throws iff failFast the exception that was thrown by first selected compiler
     */
    public TargetMethod compile(ClassMethodActor cma, Nature nature, boolean isDeopt, boolean failFast) throws Throwable {
        boolean retryRun = false;
        while (true) {
            Compilation compilation;
            boolean doCompile = true;
            synchronized (cma) {
                assert !(cma.isNative() && cma.isVmEntryPoint()) : "cannot compile JNI functions that are native";
                Object compiledState = cma.compiledState;
                compilation = compiledState instanceof Compilation ? (Compilation) compiledState : null;
                if (compilation != null && (nature == null || nature == compilation.nature)) {
                    // Only wait for a pending compilation if it is compatible with the current request.
                    // That is, the current request does not specify a special nature (nature == null)
                    // or it specifies the same nature as the pending compilation (nature == compilation.nature)
                    if (retryRun) {
                        assert compilation.compilingThread == Thread.currentThread();
                        assert nature == null : "cannot retry if specific compilation nature is specified";
                        compilation.compiler = selectRetryCompiler(cma, nature, compilation.compiler);
                    } else {
                        // the method is currently being compiled, just wait for the result
                        doCompile = false;
                    }
                } else {
                    Compilations prevCompilations = compilation != null ? compilation.prevCompilations :  (Compilations) compiledState;
                    RuntimeCompiler compiler = selectCompiler(cma, nature, isDeopt);
                    if (retryRun) {
                        compiler = selectRetryCompiler(cma, nature, compiler);
                    }
                    compilation = new Compilation(compiler, cma, prevCompilations, Thread.currentThread(), nature, isDeopt);
                    cma.compiledState = compilation;
                }
            }

            try {
                if (doCompile) {
                    TargetMethod tm = compilation.compile();
                    VMTI.handler().methodCompiled(cma);
		    //Log.println("DONE VMTI.handler().methodCompiled(cma)");
                    return tm;
                } else {
                    // return result from other thread (which will have send the VMTI event)
                    return compilation.get();
                }
            } catch (Throwable t) {
                if (VMOptions.verboseOption.verboseCompilation) {
                    boolean lockDisabledSafepoints = Log.lock();
                    Log.printCurrentThread(false);
                    Log.print(": Compilation of " + cma + " by " + compilation.compiler + " failed");
                    t.printStackTrace(Log.out);
                    Log.unlock(lockDisabledSafepoints);
                }
                if (failFast) {
                    throw t;
                }
                if (!FailOverCompilation || retryRun || (baselineCompiler == null) || (isHosted() && compilation.compiler == optimizingCompiler)) {
                    // This is the final failure: no other compilers available or failover is disabled
                    throw FatalError.unexpected("Compilation of " + cma + " by " + compilation.compiler + " failed (final attempt)", t);
                }
                retryRun = true;
                if (VMOptions.verboseOption.verboseCompilation) {
                    boolean lockDisabledSafepoints = Log.lock();
                    Log.printCurrentThread(false);
                    Log.println(": Retrying with " + selectRetryCompiler(cma, nature, compilation.compiler) + "...");
                    Log.unlock(lockDisabledSafepoints);
                }
            }
        }
    }

    /**
     * Select the appropriate compiler based on the current state of the method.
     *
     * @param cma the class method actor to compile
     * @param nature the specific type of target method required or {@code null} if any target method is acceptable
     * @param isDeopt TODO
     * @return the compiler that should be used to perform the next compilation of the method
     */
    protected RuntimeCompiler selectCompiler(ClassMethodActor cma, RuntimeCompiler.Nature nature, boolean isDeopt) {
        String reason;
        RuntimeCompiler compiler;

        if (Actor.isUnsafe(cma.compilee().flags())) {
            assert nature != Nature.BASELINE : "cannot produce baseline version of " + cma;
            reason = "unsafe";
            compiler = optimizingCompiler;
        } else {
            if (nature == Nature.BASELINE) {
                compiler = baselineCompiler;
                reason = "nature:baseline";
                assert compiler != null;
            } else if (nature == Nature.OPT) {
                reason = "nature:opt";
                compiler = optimizingCompiler;
            } else {
                // The -XX:CompileCommand is only considered if a specific nature was not specified
                String compilerName = compilerFor(cma);
                reason = null;
                compiler = null;
                if (VMOptions.verboseOption.verboseCompilation) {
                    Log.println("attempting to set compiler as " + compilerName);
                }
                if (compilerName != null) {
                    if (optimizingCompiler != null && optimizingCompiler.matches(compilerName)) {
                        compiler = optimizingCompiler;
                        reason = "CompileCommand";
                    } else if (baselineCompiler != null && baselineCompiler.matches(compilerName)) {
                        compiler = baselineCompiler;
                        reason = "CompileCommand";
                    } else if (altCompilers != null) {
                        compiler = altCompilers.get(compilerName);
                        if (compiler != null) {
                            reason = "CompileCommand";
                        }
                    }
                }
                if (reason == null) {
                    if (isHosted()) {
                        // at prototyping time, default to the opt compiler
                        compiler = optimizingCompiler;
                    } else {
                        if (VMTI.handler().hasBreakpoints(cma)) {
                            reason = "vmti";
                            compiler = baselineCompiler;
                        } else if (!isDeopt && cma.isVM() && VMExtOpt) {
                            // compile VM extensions with the opt compiler (cf isHosted)
                            reason = "vm";
                            compiler = optimizingCompiler;
                        } else {
                            compiler = defaultCompiler;
                        }
                    }
                }
            }
        }

        // Print the reason for the compiler selection if it's not the default
        if (VMOptions.verboseOption.verboseCompilation && reason != null) {
            String methodString = cma.format("%H.%n(%p)");
            boolean lockDisabledSafepoints = Log.lock();
            Log.printCurrentThread(false);
            Log.print(": ");
            Log.print(compiler.getClass().getSimpleName());
            Log.print(" selected to compile ");
            Log.print(methodString);
            Log.print(", reason: ");
            Log.println(reason);
            Log.unlock(lockDisabledSafepoints);
        }

        return compiler;
    }

    /**
     * Select the appropriate compiler to retry compilation based on the current state of the method
     * and the previous compiler.
     *
     * @param cma the class method actor to compile
     * @param nature the specific type of target method required or {@code null} if any target method is acceptable
     * @param previousCompiler compiler compiler that already tried to compile
     * @return the compiler that should be used to perform the next compilation of the method
     */
    protected RuntimeCompiler selectRetryCompiler(ClassMethodActor cma, RuntimeCompiler.Nature nature, RuntimeCompiler previousCompiler) {
        if (previousCompiler == optimizingCompiler) {
            return baselineCompiler;
        } else {
            return optimizingCompiler;
        }
    }

    /**
     * Reset the compiled state for a given method. This method
     * should only be used in very specific circumstances to force recompilation of a method and is NOT FOR GENERAL
     * USE.
     *
     * @param cma the method for which to reset the method state
     */
    @HOSTED_ONLY
    public static void resetCompiledState(ClassMethodActor cma) {
        cma.compiledState = Compilations.EMPTY;
    }

    /**
     * Handles an instrumentation counter overflow upon entry to a profiled method.
     * This method must be called on the thread that overflowed the counter.
     *
     * @param mpo profiling object (including the method itself)
     * @param receiver the receiver object of the profiled method. This will be {@code null} if the profiled method is static.
     */
    public static void instrumentationCounterOverflow(MethodProfile mpo, Object receiver) {
        if (mpo.compilationDisabled) {
            mpo.entryCount = Integer.MAX_VALUE;
            return;
        }
        if (Heap.isAllocationDisabledForCurrentThread()) {
            logCounterOverflow(mpo, "Stopped recompilation because allocation is currently disabled");
            // We don't want to see another counter overflow in the near future
            mpo.entryCount = 1000;
            return;
        }
        if (Compilation.isCompilationRunningInCurrentThread()) {
            logCounterOverflow(mpo, "Stopped recompilation because compilation is running in current thread");
            // We don't want to see another counter overflow in the near future
            mpo.entryCount = 1000;
            return;
        }

        ClassMethodActor cma = mpo.method.classMethodActor;
        TargetMethod oldMethod = mpo.method;
        TargetMethod newMethod = Compilations.currentTargetMethod(cma.compiledState, null);

        if (oldMethod == newMethod || newMethod == null) {
            if (!(cma.compiledState instanceof Compilation)) {
                // There is no newer compiled version available yet that we could just patch to, so recompile
                logCounterOverflow(mpo, "");
                try {
                    newMethod = vm().compilationBroker.compile(cma, Nature.OPT);
                } catch (InternalError e) {
                    if (VMOptions.verboseOption.verboseCompilation) {
                        e.printStackTrace(Log.out);
                    }
                    // Optimization failed - stay with the baseline method. By not resetting the counter,
                    // the next counter overflow (due to integer wrapping) will be a while away.
                    return;
                }
            }
        }


        if (oldMethod == newMethod || newMethod == null) {
            // No compiled method available yet, maybe compilation is pending.
            // We don't want to see another counter overflow in the near future.
            mpo.entryCount = 10000;
        } else {
            assert newMethod != null : oldMethod;
            logPatching(cma, oldMethod, newMethod);
            mpo.entryCount = 0;

            if (receiver != null) {
                Address from = oldMethod.getEntryPoint(VTABLE_ENTRY_POINT).toAddress();
                Address to = newMethod.getEntryPoint(VTABLE_ENTRY_POINT).toAddress();

                // Simply overwrite all vtable slots containing 'oldMethod' with 'newMethod'.
                // These updates can be made atomically without need for a lock.
                Hub hub = ObjectAccess.readHub(receiver);
                for (int i = 0; i < hub.vTableLength(); i++) {
                    int index = Hub.vTableStartIndex() + i;
                    if (hub.getWord(index).equals(from)) {
                        logDispatchTablePatch(cma, from, to, hub, index, "vtable");
                        hub.setWord(index, to);
                    }
                }

                for (int i = 0; i < hub.iTableLength; i++) {
                    int index = hub.iTableStartIndex + i;
                    if (hub.getWord(index).equals(from)) {
                        logDispatchTablePatch(cma, from, to, hub, index, "itable");
                        hub.setWord(index, to);
                    }
                }
            }

            // Look for a static call to 'oldMethod' and patch it.
            // This occurs even if 'cma' is non-static
            // as it may have been called directly.
            DirectCallPatcher patcher = new DirectCallPatcher(oldMethod, newMethod);
            new VmStackFrameWalker(VmThread.current().tla()).inspect(Pointer.fromLong(here()),
                    VMRegister.getCpuStackPointer(),
                    VMRegister.getCpuFramePointer(),
                    patcher);
        }
    }

    public static void logCounterOverflow(MethodProfile mpo, String msg) {
        if (VMOptions.verboseOption.verboseCompilation) {
            boolean lockDisabledSafepoints = Log.lock();
            Log.printCurrentThread(false);
            Log.print(": Invocation counter overflow of ");
            Log.printMethod(mpo.method, false);
            Log.print(" counter ");
            Log.print(mpo.entryCount);
            Log.print("  ");
            Log.print(msg);
            Log.println();
            Log.unlock(lockDisabledSafepoints);
        }
    }

    private static void logPatching(ClassMethodActor cma, TargetMethod oldMethod, TargetMethod newMethod) {
        if (verboseOption.verboseCompilation) {
            boolean lockDisabledSafepoints = Log.lock();
            Log.printCurrentThread(false);
            Log.print(": Patching for method ");
            Log.printMethod(cma, false);
            Log.print(" oldMethod ");
            Log.print(oldMethod.getEntryPoint(BASELINE_ENTRY_POINT));
            Log.print(" newMethod ");
            Log.print(newMethod.getEntryPoint(BASELINE_ENTRY_POINT));
            Log.println();
            Log.unlock(lockDisabledSafepoints);
        }
    }

    private static void logDispatchTablePatch(ClassMethodActor cma, final Address from, final Address to, Hub hub, int index, String table) {
        if (verboseOption.verboseCompilation) {
            boolean lockDisabledSafepoints = Log.lock();
            Log.printCurrentThread(false);
            Log.print(": Patching ");
            Log.print(hub.classActor.name());
            Log.print('.');
            Log.print(table);
            Log.print('[');
            Log.print(index);
            Log.print("] {");
            Log.printMethod(cma, false);
            Log.print("} ");
            Log.print(from);
            Log.print(" -> ");
            Log.println(to);
            Log.unlock(lockDisabledSafepoints);
        }
    }

    private static void logStaticCallPatch(StackFrameCursor current, CodePointer callSite, int dcIndex, CodePointer to) {
        if (verboseOption.verboseCompilation) {
            boolean lockDisabledSafepoints = Log.lock();
            Log.printCurrentThread(false);
            Log.print(": Patching static call at ");
            Log.printLocation(current.targetMethod(), callSite, false);
            Log.print(" (direct callee index ");
            Log.print(dcIndex);
            Log.print(") to ");
            Log.println(to);
            Log.unlock(lockDisabledSafepoints);
        }
    }

    private static void logNoFurtherStaticCallPatching() {
        if (verboseOption.verboseCompilation) {
            boolean lockDisabledSafepoints = Log.lock();
            Log.printCurrentThread(false);
            Log.println(": No further patching of static calls");
            Log.unlock(lockDisabledSafepoints);
        }
    }

    /**
     * This class implements a daemon thread that performs compilations in the background. Depending on the compiler
     * configuration, multiple compilation threads may be working in parallel.
     */
    protected class CompilationThread extends Thread {

        protected CompilationThread() {
            super("compile");
            setDaemon(true);
        }

        /**
         * The current compilation being performed by this thread.
         */
        Compilation compilation;

        /**
         * Continuously polls the compilation queue for work, performing compilations as they are removed from the
         * queue.
         */
        @Override
        public void run() {
            while (true) {
                try {
                    compileOne();
                } catch (InterruptedException e) {
                    // do nothing.
                } catch (Throwable t) {
                    Log.print("Exception during compilation of " + compilation.classMethodActor);
                    t.printStackTrace();
                }
            }
        }

        /**
         * Polls the compilation queue and performs a single compilation.
         *
         * @throws InterruptedException if the thread was interrupted waiting on the queue
         */
        void compileOne() throws InterruptedException {
            compilation = null;
            synchronized (pending) {
                while (compilation == null) {
                    compilation = pending.poll();
                    if (compilation == null) {
                        pending.wait();
                    }
                }
            }
            compilation.compilingThread = Thread.currentThread();
            if (GCOnRecompilation) {
                System.gc();
            }
            compilation.compile();
            compilation = null;
        }
    }

    /**
     * Helper class for patching any direct call sites on the stack corresponding to a target method
     * being replaced by a recompiled version.
     */
    static class DirectCallPatcher extends RawStackFrameVisitor {

        /**
         * The maximum number of frames to search for a patchable direct call site.
         */
        static final int FRAME_SEARCH_LIMIT = 10;

        private final TargetMethod oldMethod;
        private final TargetMethod newMethod;
        int frameCount;

        public DirectCallPatcher(TargetMethod oldMethod, TargetMethod newMethod) {
            this.oldMethod = oldMethod;
            this.newMethod = newMethod;
        }

        private int directCalleePosition(TargetMethod tm, CodePointer callSite) {
            final Safepoints safepoints = tm.safepoints();
            final int callPos = callSite.minus(tm.codeStart()).toInt();
            int dcIndex = 0;
            for (int i = 0; i < safepoints.size(); i++) {
                if (safepoints.isSetAt(DIRECT_CALL, i)) {
                    if (safepoints.causePosAt(i) == callPos) {
                        return dcIndex;
                    }
                    dcIndex++;
                }
            }
            return -1;
        }

        @Override
        public boolean visitFrame(StackFrameCursor current, StackFrameCursor callee) {
            if (platform().isa == ISA.ARM) {
                if (current.isTopFrame()) {
                    return true;
                }
                Pointer ip = current.ipAsPointer();
                CodePointer callSite = CodePointer.from(ip.minus(ARMTargetMethodUtil.RIP_CALL_INSTRUCTION_SIZE));
                Pointer callSitePointer = callSite.toPointer();
                if ((callSitePointer.readByte(0) & 0xFF) == ARMTargetMethodUtil.RIP_CALL) {
                    CodePointer target = CodePointer.from(ip.plus(callSitePointer.readInt(1)));
                    if (target.equals(oldMethod.getEntryPoint(BASELINE_ENTRY_POINT))) {
                        final CodePointer to = newMethod.getEntryPoint(BASELINE_ENTRY_POINT);
                        final TargetMethod tm = current.targetMethod();
                        final int dcIndex = directCalleePosition(tm, callSite);
                        assert dcIndex != -1 : "no valid direct callee for call site " + callSite.to0xHexString();
                        logStaticCallPatch(current, callSite, dcIndex, to);
                        ARMTargetMethodUtil.mtSafePatchCallDisplacement(tm, callSite, to);
                        // Stop traversing the stack after a direct call site has been patched
                        return false;
                    }
                    if (target.equals(oldMethod.getEntryPoint(OPTIMIZED_ENTRY_POINT))) {
                        final CodePointer to = newMethod.getEntryPoint(OPTIMIZED_ENTRY_POINT);
                        final TargetMethod tm = current.targetMethod();
                        final int dcIndex = directCalleePosition(tm, callSite);
                        assert dcIndex != -1 : "no valid direct callee for call site " + callSite.to0xHexString();
                        logStaticCallPatch(current, callSite, dcIndex, to);
                        ARMTargetMethodUtil.mtSafePatchCallDisplacement(tm, callSite, to);
                        // Stop traversing the stack after a direct call site has been patched
                        return false;
                    }
                }
                if (++frameCount > FRAME_SEARCH_LIMIT) {
                    logNoFurtherStaticCallPatching();
                    return false;
                }
                return true;

            }
            if (platform().isa == ISA.AMD64) {
                if (current.isTopFrame()) {
                    return true;
                }
                Pointer ip = current.ipAsPointer();
                CodePointer callSite = CodePointer.from(ip.minus(AMD64TargetMethodUtil.RIP_CALL_INSTRUCTION_SIZE));
                Pointer callSitePointer = callSite.toPointer();
                if ((callSitePointer.readByte(0) & 0xFF) == AMD64TargetMethodUtil.RIP_CALL) {
                    CodePointer target = CodePointer.from(ip.plus(callSitePointer.readInt(1)));
                    if (target.equals(oldMethod.getEntryPoint(BASELINE_ENTRY_POINT))) {
                        final CodePointer to = newMethod.getEntryPoint(BASELINE_ENTRY_POINT);
                        final TargetMethod tm = current.targetMethod();
                        final int dcIndex = directCalleePosition(tm, callSite);
                        assert dcIndex != -1 : "no valid direct callee for call site " + callSite.to0xHexString();
                        logStaticCallPatch(current, callSite, dcIndex, to);
                        AMD64TargetMethodUtil.mtSafePatchCallDisplacement(tm, callSite, to);
                        // Stop traversing the stack after a direct call site has been patched
                        return false;
                    }
                    if (target.equals(oldMethod.getEntryPoint(OPTIMIZED_ENTRY_POINT))) {
                        final CodePointer to = newMethod.getEntryPoint(OPTIMIZED_ENTRY_POINT);
                        final TargetMethod tm = current.targetMethod();
                        final int dcIndex = directCalleePosition(tm, callSite);
                        assert dcIndex != -1 : "no valid direct callee for call site " + callSite.to0xHexString();
                        logStaticCallPatch(current, callSite, dcIndex, to);
                        AMD64TargetMethodUtil.mtSafePatchCallDisplacement(tm, callSite, to);
                        // Stop traversing the stack after a direct call site has been patched
                        return false;
                    }
                }
                if (++frameCount > FRAME_SEARCH_LIMIT) {
                    logNoFurtherStaticCallPatching();
                    return false;
                }
                return true;
            }
            throw FatalError.unimplemented();
        }
    }

    /**
     * Compiler that can be used to create a functional Maxine runtime (albeit without compilation)
     * when a specified compiler can't be found on the class path.
     */
    @HOSTED_ONLY
    public static class NullCompiler extends RuntimeCompiler.DefaultNameAdapter implements RuntimeCompiler {

        public final Nature nature;

        public NullCompiler(Nature nature) {
            this.nature = nature;
        }

        public void initialize(Phase phase) {
        }
        public TargetMethod compile(ClassMethodActor classMethodActor, boolean isDeopt, boolean install, CiStatistics stats) {
            return null;
        }
        public Nature nature() {
            return nature;
        }
        public boolean matches(String compilerName) {
            return true;
        }
        @Override
        public String toString() {
            return getClass().getSimpleName() + "@" + Integer.toHexString(hashCode()) + "[" + nature + "]";
        }
    }

    public static class NullOptCompiler extends NullCompiler {
        public NullOptCompiler() {
            super(Nature.OPT);
        }
    }

    public static class NullBaselineCompiler extends NullCompiler {
        public NullBaselineCompiler() {
            super(Nature.BASELINE);
        }
    }
}
