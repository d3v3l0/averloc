import java.io.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;
import java.util.zip.GZIPInputStream;
import java.lang.Thread;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService; 
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import transforms.*;

import com.google.gson.*;

import spoon.Launcher;
import spoon.reflect.cu.*;
import spoon.reflect.CtModel;
import spoon.reflect.cu.position.*;
import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.*;

import spoon.OutputType;
import spoon.processing.AbstractProcessor;
import spoon.support.JavaOutputProcessor;
import spoon.support.compiler.VirtualFile;
import spoon.reflect.visitor.filter.TypeFilter;
import spoon.reflect.visitor.DefaultJavaPrettyPrinter;
import spoon.reflect.visitor.PrettyPrinter;

class TransformFileTask implements Runnable {	
	static AtomicInteger counter = new AtomicInteger(0); // a global counter

	String split;
	ArrayList<VirtualFile> inputs;

	TransformFileTask(String split, ArrayList<VirtualFile> inputs) {
		this.split = split;
		this.inputs = inputs;
	}

	private Launcher buildLauncher(AverlocTransformer transformer) {
		Launcher launcher = new Launcher();

		launcher.getEnvironment().setCopyResources(false);
		launcher.getEnvironment().setNoClasspath(true);
		launcher.getEnvironment().setShouldCompile(false);
		launcher.getEnvironment().setLevel("OFF");
		launcher.getEnvironment().setOutputType(OutputType.NO_OUTPUT);

		launcher.addProcessor(transformer);

		launcher.setSourceOutputDirectory(
			String.format("/mnt/raw-outputs/%s/%s", transformer.getOutName(), split)
		);

		return launcher;
	}

	public void outputFiles(CtModel model, AverlocTransformer transformer) {
		for (CtClass outputClass : model.getElements(new TypeFilter<>(CtClass.class))) {
			if (transformer.changes(outputClass.getSimpleName())) {
				Path path = Paths.get(
					String.format(
						"/mnt/raw-outputs/%s/%s/%s.java",
						transformer.getOutName(),
						split,
						outputClass.getSimpleName().replace("WRAPPER_", "")
					)
				);

				if (!path.getParent().toFile().exists()) {
					path.getParent().toFile().mkdirs();
				}

				try {
					File file = path.toFile();
					file.createNewFile();
		
					PrintStream stream = new PrintStream(file);
					stream.print(outputClass.toString());
				} catch (Exception ex) {
					System.out.println("Failed to save: " + path.toString());
					ex.printStackTrace(System.out);
					continue;
				}
			}
		}
	}

	public void run() {
		ArrayList<AverlocTransformer> transformers = new ArrayList<AverlocTransformer>();

		transformers.add(new Identity());

		boolean doDepthK = System.getenv("DEPTH_K") != null;
    Random rand = new Random();

		if (doDepthK) {
			int K = Integer.parseInt(System.getenv("DEPTH_K"));
			int SAMPLES = Integer.parseInt(System.getenv("SAMPLES"));

			// Take SAMPLES many sequences of DEPTH_K length
			for (int s = 0; s < SAMPLES; s++) {
				ArrayList<AverlocTransformer> subset = new ArrayList<AverlocTransformer>();
				
				// Random, allow duplciates, do depth K
				for (int i = 0; i < K; i++) {
					int choice = rand.nextInt(8);

					if (choice == 0){
						subset.add(new AddDeadCode(i));
					} else if (choice == 1) {
						transformers.add(new WrapTryCatch(i));
					} else if (choice == 2) {
						transformers.add(new UnrollWhiles(i));
					} else if (choice == 3) {
						transformers.add(new InsertPrintStatements(i));
					} else if (choice == 4) {
						transformers.add(new RenameFields(i));
					} else if (choice == 5) {
						transformers.add(new RenameLocalVariables(i));
					} else if (choice == 6) {
						transformers.add(new RenameParameters(i));
					} else if (choice == 7) {
						transformers.add(new ReplaceTrueFalse(i));
					}
				}

				transformers.add(new Sequenced(
					subset,
					"depth-" + Integer.toString(K) + "-sample-" + Integer.toString(s + 1)
				));

			}
		} else {
			transformers.add(new AddDeadCode(1));
			transformers.add(new WrapTryCatch(1));
			transformers.add(new UnrollWhiles(1));
			transformers.add(new InsertPrintStatements(1));
			transformers.add(new RenameFields(1));
			transformers.add(new RenameLocalVariables(1));
			transformers.add(new RenameParameters(1));
			transformers.add(new ReplaceTrueFalse(1));
		}

		// System.out.println(String.format("     + Have %s tranforms.", transformers.size()));

		ArrayList<String> failures = new ArrayList<String>();
		for (AverlocTransformer transformer : transformers) {
			try {
				Launcher launcher = buildLauncher(transformer);

				for (VirtualFile input : inputs) {
					launcher.addInputResource(input);
				}

				CtModel model = launcher.buildModel();
				model.processWith(transformer);

				outputFiles(model, transformer);
			} catch (Exception ex1) {

				for (VirtualFile singleInput : inputs) {
					if (failures.contains(singleInput.getName())) {
						continue;
					}

					try {
						Launcher launcher = buildLauncher(transformer);
						launcher.addInputResource(singleInput);

						CtModel model = launcher.buildModel();
						model.processWith(transformer);

						outputFiles(model, transformer);
					} catch (Exception ex2) {
						System.out.println(
							String.format(
								"     * Failed to build model for: %s",
								singleInput.getName()
							)
						);
						failures.add(singleInput.getName());
					}
				}
			}
		}

		int finished = counter.incrementAndGet();
		System.out.println(String.format("     + Tasks finished: %s", finished));
	
	}
}

public class Transforms {
	private static Callable<Void> toCallable(final Runnable runnable) {
    return new Callable<Void>() {
        @Override
        public Void call() {
					try {
            runnable.run();
					} catch (Exception e) {
						e.printStackTrace(System.err);
						System.err.println(e.toString());
					}
						return null;
        }
    };
	} 
	
	private static <T> ArrayList<ArrayList<T>> chopped(ArrayList<T> list, final int L) {
    ArrayList<ArrayList<T>> parts = new ArrayList<ArrayList<T>>();
    final int N = list.size();
    for (int i = 0; i < N; i += L) {
        parts.add(new ArrayList<T>(
            list.subList(i, Math.min(N, i + L)))
        );
    }
    return parts;
  }

	private static ArrayList<Callable<Void>> makeTasks(String split) {
		try {
			// Return list of tasks
			ArrayList<Callable<Void>> tasks = new ArrayList<Callable<Void>>();
		

			// The file this thread will read from
			InputStream fileStream = new FileInputStream(String.format(
				"/mnt/inputs/%s.jsonl.gz",
				split
			));

			// File (gzipped) -> Decoded Stream -> Lines
			InputStream gzipStream = new GZIPInputStream(fileStream);
			Reader decoder = new InputStreamReader(gzipStream, "UTF-8");
			BufferedReader buffered = new BufferedReader(decoder);

			// From gzip, create virtual files
			String line;
			JsonParser parser = new JsonParser();
			ArrayList<VirtualFile> inputs = new ArrayList<VirtualFile>();
			while ((line = buffered.readLine()) != null) {
				JsonObject asJson = parser.parse(line).getAsJsonObject();

				inputs.add(new VirtualFile(
					asJson.get("source_code").getAsString().replace(
						"class WRAPPER {",
						String.format(
							"class WRAPPER_%s {",
							asJson.get("sha256_hash").getAsString()
						)
					),
					String.format("%s.java", asJson.get("sha256_hash").getAsString())
				));
			}

			for (ArrayList<VirtualFile> chunk : chopped(inputs, 3000)) {
				tasks.add(toCallable(new TransformFileTask(split, chunk)));
			}

			return tasks;
		}
		catch (Exception ex) {
			ex.printStackTrace();
			System.out.println(ex.toString());
			return new ArrayList<Callable<Void>>();
		}
	}

	public static void main(String[] args) {
		try {
			ArrayList<Callable<Void>> allTasks = new ArrayList<Callable<Void>>();

			if (System.getenv("AVERLOC_JUST_TEST").equalsIgnoreCase("true")) {
				System.out.println("Populating tasks...");
				System.out.println("   - Adding from test split...");
				allTasks.addAll(Transforms.makeTasks("test"));
				System.out.println(String.format("     + Now have %s tasks...", allTasks.size()));
			} else {
				System.out.println("Populating tasks...");
				System.out.println("   - Adding from test split...");
				allTasks.addAll(Transforms.makeTasks("test"));
				System.out.println(String.format("     + Now have %s tasks...", allTasks.size()));
				System.out.println("   - Adding from train split...");
				allTasks.addAll(Transforms.makeTasks("train"));
				System.out.println(String.format("     + Now have %s tasks...", allTasks.size()));
				System.out.println("   - Adding from valid split...");
				allTasks.addAll(Transforms.makeTasks("valid"));
				System.out.println(String.format("     + Now have %s tasks...", allTasks.size()));
			}

			System.out.println("   - Running in parallel with 64 threads...");
			ExecutorService pool = Executors.newFixedThreadPool(64);

			// allTasks.get(4).call();
			pool.invokeAll(allTasks);
			// pool.invokeAll(allTasks.stream().limit(10).collect(Collectors.toList()));

			pool.shutdown(); 
			System.out.println("   + Done!");
		} catch (Exception ex) {
			ex.printStackTrace();
			System.out.println(ex.toString());
		}
	}

}
