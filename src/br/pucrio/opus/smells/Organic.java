package br.pucrio.opus.smells;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import com.google.gson.ReflectionAccessFilter;
import org.apache.commons.cli.ParseException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import br.pucrio.opus.smells.agglomeration.Agglomeration;
import br.pucrio.opus.smells.agglomeration.AgglomerationFinder;
import br.pucrio.opus.smells.agglomeration.SmellyGraph;
import br.pucrio.opus.smells.agglomeration.SmellyGraphBuilder;
import br.pucrio.opus.smells.collector.ClassLevelSmellDetector;
import br.pucrio.opus.smells.collector.MethodLevelSmellDetector;
import br.pucrio.opus.smells.collector.Smell;
import br.pucrio.opus.smells.gson.ObservableExclusionStrategy;
import br.pucrio.opus.smells.metrics.MethodMetricValueCollector;
import br.pucrio.opus.smells.metrics.TypeMetricValueCollector;
import br.pucrio.opus.smells.resources.JavaFilesFinder;
import br.pucrio.opus.smells.resources.Method;
import br.pucrio.opus.smells.resources.SourceFile;
import br.pucrio.opus.smells.resources.SourceFilesLoader;
import br.pucrio.opus.smells.resources.Type;
import br.pucrio.opus.smells.util.OrganicOptions;

public class Organic {

	private static Organic instance = null;
	private List<Type> allTypes;

	private Organic(){}

	public static Organic getInstance(){
		if (instance == null){
			instance = new Organic();
		}
		return instance;
	}

	public List<Type> getAllTypes(){
		return allTypes;
	}

	private void collectMethodMetrics(Type type) {
		for (Method method: type.getMethods()) {
			MethodMetricValueCollector methodCollector = new MethodMetricValueCollector(type.getNodeAsTypeDeclaration());
			methodCollector.collect(method);
		}
	}

	private void collectTypeMetrics(List<Type> types) throws IOException {
		for (Type type : types) {
			TypeMetricValueCollector typeCollector = new TypeMetricValueCollector();
			typeCollector.collect(type);
			this.collectMethodMetrics(type);
		}
	}

	private void detectSmells(List<Type> allTypes) {
		for (Type type : allTypes) {
			// It is important for some detectors that method-level smells are collected first
			for (Method method: type.getMethods()) {
				MethodLevelSmellDetector methodSmellDetector = new MethodLevelSmellDetector();
				List<Smell> smells = methodSmellDetector.detect(method);
				method.addAllSmells(smells);
			}
			// some class-level detectors use method-level smells in their algorithms
			ClassLevelSmellDetector classSmellDetector = new ClassLevelSmellDetector();
			List<Smell> smells = classSmellDetector.detect(type);
			type.addAllSmells(smells);
		}
	}

	private List<Type> loadAllTypes(List<String> sourcePaths) throws IOException {
		List<Type> allTypes = new ArrayList<>();
		JavaFilesFinder sourceLoader = new JavaFilesFinder(sourcePaths);
		SourceFilesLoader compUnitLoader = new SourceFilesLoader(sourceLoader);
		List<SourceFile> sourceFiles = compUnitLoader.getLoadedSourceFiles();
		for (SourceFile sourceFile : sourceFiles) {
			allTypes.addAll(sourceFile.getTypes());
		}
		return allTypes;
	}

	private List<Type> onlySmelly(List<Type> types) {
		List<Type> smelly = new ArrayList<>();
		for (Type type : types) {
			if (type.isSmelly()) {
				type.removeAllNonSmellyMethods();
				smelly.add(type);
			}
		}
		return smelly;
	}
	
	private void saveSmellsFile(List<Type> smellyTypes) throws IOException {
		OrganicOptions options = OrganicOptions.getInstance();
		File smellsFile = new File(options.getValue(OrganicOptions.SMELLS_FILE));
		BufferedWriter writer = new BufferedWriter(new FileWriter(smellsFile));
		System.out.println("Saving smells file...");

		GsonBuilder builder = new GsonBuilder();
		builder.addSerializationExclusionStrategy(new ObservableExclusionStrategy());
		builder.addReflectionAccessFilter(ReflectionAccessFilter.BLOCK_INACCESSIBLE_JAVA);
		builder.disableHtmlEscaping();
		builder.setPrettyPrinting();
		builder.serializeNulls();

		Gson gson = builder.create();
		gson.toJson(smellyTypes, writer);
		writer.close();
	}
	
	private void saveAgglomerationsFile(List<Agglomeration> agglomerations) throws IOException {
		OrganicOptions options = OrganicOptions.getInstance();
		File smellsFile = new File(options.getValue(OrganicOptions.AGGLOMERATIONS_FILE));
		BufferedWriter writer = new BufferedWriter(new FileWriter(smellsFile));
		System.out.println("Saving agglomeration file...");

		GsonBuilder builder = new GsonBuilder();
		builder.addSerializationExclusionStrategy(new ObservableExclusionStrategy());
		builder.disableHtmlEscaping();
		builder.setPrettyPrinting();
		builder.serializeNulls();

		Gson gson = builder.create();
		gson.toJson(agglomerations, writer);
		writer.close();
	}
	
	private List<Agglomeration> collectAgglomerations(List<Type> allTypes) {
		System.out.println("Collecting agglomerations");
		SmellyGraphBuilder builder = new SmellyGraphBuilder();
		builder.addTypeAndItsMethods(allTypes);
		System.out.println("Building the smelly graph");
		SmellyGraph graph = builder.build();
		System.out.println("Looking for agglomerations");
		AgglomerationFinder finder = new AgglomerationFinder(graph);
		return finder.findAll();
	}

	public int start(String[] args) throws IOException {
		OrganicOptions options = OrganicOptions.getInstance();
		try {
			options.parse(args);
		} catch( ParseException exp ) {
			System.out.println(exp.getMessage());
			options.printHelp();
			System.exit(-1);
		}

		System.out.println(new Date());
		List<String> sourcePaths = Arrays.asList(options.getValue(OrganicOptions.SOURCE_FOLDER));
		allTypes = this.loadAllTypes(sourcePaths);
		this.collectTypeMetrics(allTypes);
		this.detectSmells(allTypes);
		if (options.shouldFilterSmelly()) {
			allTypes = this.onlySmelly(allTypes);
		}

		this.saveSmellsFile(allTypes);
		if (options.shouldCollectAgglomerations()) {
			List<Agglomeration> agglomerations = collectAgglomerations(allTypes);
			this.saveAgglomerationsFile(agglomerations);
		}

		System.out.println(new Date());
		return 0;
	}

	public static void main(String[] args) throws IOException {
		Organic instance = Organic.getInstance();
		instance.start(args);
	}
}
