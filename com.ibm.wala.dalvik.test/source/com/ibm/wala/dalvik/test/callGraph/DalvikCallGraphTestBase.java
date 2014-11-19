/*******************************************************************************
 * Copyright (c) 2014 IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.wala.dalvik.test.callGraph;

import static com.ibm.wala.properties.WalaProperties.ANDROID_DEX_TOOL;
import static com.ibm.wala.properties.WalaProperties.ANDROID_RT_JAR;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.JarFileModule;
import com.ibm.wala.classLoader.Module;
import com.ibm.wala.core.tests.callGraph.CallGraphTestUtil;
import com.ibm.wala.core.tests.shrike.DynamicCallGraphTestBase;
import com.ibm.wala.dalvik.classLoader.DexIRFactory;
import com.ibm.wala.dalvik.util.AndroidAnalysisScope;
import com.ibm.wala.dalvik.util.AndroidEntryPointLocator;
import com.ibm.wala.dalvik.util.AndroidEntryPointLocator.LocatorFlags;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.AnalysisOptions.ReflectionOptions;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.ipa.callgraph.propagation.SSAPropagationCallGraphBuilder;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.properties.WalaProperties;
import com.ibm.wala.shrikeBT.analysis.Analyzer.FailureException;
import com.ibm.wala.shrikeCT.InvalidClassFileException;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.MonitorUtil.IProgressMonitor;
import com.ibm.wala.util.NullProgressMonitor;
import com.ibm.wala.util.Predicate;
import com.ibm.wala.util.WalaException;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.collections.Pair;
import com.ibm.wala.util.functions.Function;
import com.ibm.wala.util.io.TemporaryFile;

public class DalvikCallGraphTestBase extends DynamicCallGraphTestBase {
	public static Properties walaProperties;
	
	static {
		try {
			walaProperties = WalaProperties.loadProperties();
		} catch (WalaException e) {
			walaProperties = null;
			assert false : e;
		}
	}
	
	protected static <T> Set<T> processCG(CallGraph cg, Predicate<CGNode> filter, Function<CGNode,T> map) {
		Set<T> result = HashSetFactory.make();
		for(CGNode n : cg) {
			if (filter.test(n)) {
				result.add(map.apply(n));
			}
		}
		return result;
	}
	
	protected static Set<MethodReference> applicationMethods(CallGraph cg) {
		return processCG(cg,
			new Predicate<CGNode>() {
				@Override
				public boolean test(CGNode t) {
					return t.getMethod().getReference().getDeclaringClass().getClassLoader().equals(ClassLoaderReference.Application);
				}
			},
			new Function<CGNode,MethodReference>() {
				@Override
				public MethodReference apply(CGNode object) {
					return object.getMethod().getReference();
				}
			});
	}
	

	protected static String getJavaJar(AnalysisScope javaScope) {
		Module javaJar = javaScope.getModules(javaScope.getApplicationLoader()).iterator().next();
		assert javaJar instanceof JarFileModule;
		String javaJarPath = ((JarFileModule)javaJar).getAbsolutePath();
		return javaJarPath;
	}

	public static File convertJarToDex(File jarFile) throws IOException, InterruptedException {
		File f = File.createTempFile("convert", ".dex");
		f.deleteOnExit();
		Process p = Runtime.getRuntime().exec(walaProperties.getProperty(ANDROID_DEX_TOOL) + " --dex --output=" + f.getAbsolutePath() + " " + jarFile.getAbsolutePath());
		p.waitFor();
		return f;
	}
	
	public void dynamicCG(File javaJarPath, String mainClass, String... args) throws FileNotFoundException, IOException, ClassNotFoundException, InvalidClassFileException, FailureException, SecurityException, NoSuchMethodException, IllegalArgumentException, IllegalAccessException, InvocationTargetException, InterruptedException {
		File F = TemporaryFile.streamToFile(new File("test_jar.jar"), new FileInputStream(javaJarPath));
		F.deleteOnExit();
		instrument(F.getAbsolutePath());
		run(mainClass.substring(1).replace('/', '.'), "LibraryExclusions.txt", args);
	}

	public static Pair<CallGraph, PointerAnalysis<InstanceKey>> makeAPKCallGraph(String apkFileName) throws IOException, ClassHierarchyException, IllegalArgumentException, CancelException {
		return makeAPKCallGraph(apkFileName, new NullProgressMonitor());
	}
	
	public static Pair<CallGraph, PointerAnalysis<InstanceKey>> makeAPKCallGraph(String apkFileName, IProgressMonitor monitor) throws IOException, ClassHierarchyException, IllegalArgumentException, CancelException {
		AnalysisScope scope = 
			AndroidAnalysisScope.setUpAndroidAnalysisScope(
				new File(apkFileName).toURI(),
				"AndroidRegressionExclusions.txt",
				androidLibs());

		final IClassHierarchy cha = ClassHierarchy.make(scope);

		AnalysisCache cache = new AnalysisCache(new DexIRFactory());

		Set<LocatorFlags> flags = HashSetFactory.make();
		flags.add(LocatorFlags.INCLUDE_CALLBACKS);
		flags.add(LocatorFlags.EP_HEURISTIC);
		flags.add(LocatorFlags.CB_HEURISTIC);
		AndroidEntryPointLocator eps = new AndroidEntryPointLocator(flags);
		List<? extends Entrypoint> es = eps.getEntryPoints(cha);

		assert ! es.isEmpty();
		
		AnalysisOptions options = new AnalysisOptions(scope, es);
		options.setReflectionOptions(ReflectionOptions.ONE_FLOW_TO_CASTS_APPLICATION_GET_METHOD);
		
		SSAPropagationCallGraphBuilder cgb = Util.makeZeroCFABuilder(options, cache, cha, scope);
  
		CallGraph callGraph = cgb.makeCallGraph(options, monitor);
		
		PointerAnalysis<InstanceKey> ptrAnalysis = cgb.getPointerAnalysis();
		
		return Pair.make(callGraph, ptrAnalysis);
	}
	
	public static URI[] androidLibs() {
		List<URI> libs = new ArrayList<URI>();
		for(File lib : new File(walaProperties.getProperty(ANDROID_RT_JAR)).listFiles(new FilenameFilter() {
			@Override
			public boolean accept(File dir, String name) {
				return name.endsWith("dex");
			} 
		})) {
			libs.add(lib.toURI());
		}
		return libs.toArray(new URI[ libs.size() ]);
	}
	
	public static Pair<CallGraph, PointerAnalysis<InstanceKey>> makeDalvikCallGraph(boolean useAndroidLib, String mainClassName, String dexFileName) throws IOException, ClassHierarchyException, IllegalArgumentException, CancelException {
		AnalysisScope scope = 
			useAndroidLib?
			AndroidAnalysisScope.setUpAndroidAnalysisScope(
				new File(dexFileName).toURI(), 
				CallGraphTestUtil.REGRESSION_EXCLUSIONS,
				androidLibs()):
			AndroidAnalysisScope.setUpAndroidAnalysisScope(
				new File(dexFileName).toURI(), 
				CallGraphTestUtil.REGRESSION_EXCLUSIONS);
		
		final IClassHierarchy cha = ClassHierarchy.make(scope);

		TypeReference mainClassRef = TypeReference.findOrCreate(ClassLoaderReference.Application, mainClassName);
		IClass mainClass = cha.lookupClass(mainClassRef);
		assert mainClass != null;

		System.err.println("building call graph for " + mainClass + ":" + mainClass.getClass());
		
		Iterable<Entrypoint> entrypoints = Util.makeMainEntrypoints(scope, cha, mainClassName);
		
		AnalysisCache cache = new AnalysisCache(new DexIRFactory());

		AnalysisOptions options = new AnalysisOptions(scope, entrypoints);

		SSAPropagationCallGraphBuilder cgb = Util.makeZeroCFABuilder(options, cache, cha, scope);
  
		CallGraph callGraph = cgb.makeCallGraph(options);

		MethodReference mmr = MethodReference.findOrCreate(mainClassRef, "main", "([Ljava/lang/String;)V");
		assert !callGraph.getNodes(mmr).isEmpty();
		
		PointerAnalysis<InstanceKey> ptrAnalysis = cgb.getPointerAnalysis();
		
		return Pair.make(callGraph, ptrAnalysis);
	}
}
