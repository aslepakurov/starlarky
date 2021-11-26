package com.verygood.security.run;

import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.events.Reporter;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

import com.verygood.security.larky.ModuleSupplier;
import com.verygood.security.larky.console.CapturingConsole;
import com.verygood.security.larky.console.Console;
import com.verygood.security.larky.console.FileConsole;
import com.verygood.security.larky.console.LogConsole;
import com.verygood.security.larky.debug.StarlarkDebuggerModule;
import com.verygood.security.larky.parser.LarkyScript;
import com.verygood.security.larky.parser.LarkyScript.StarlarkMode;
import com.verygood.security.larky.parser.PrependMergedStarFile;

import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.SyntaxError;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import lombok.SneakyThrows;

@QuarkusMain
public class LarkyEntrypoint
    implements QuarkusApplication
{

  //REPL
  private static final String START_PROMPT = ">> ";
  private static final String CONTINUATION_PROMPT = ".. ";
  private static final FileOptions OPTIONS = FileOptions.DEFAULT;
  private static final BufferedReader reader =
      new BufferedReader(new InputStreamReader(System.in, UTF_8));
  private static final StarlarkThread thread;
  private static final Module module = Module.create();

  static {
    Mutability mu = Mutability.create("interpreter");
    thread = new StarlarkThread(mu, StarlarkSemantics.DEFAULT);
    thread.setPrintHandler((th, msg) -> System.out.println(msg));
  }
  //REPL end

  @SneakyThrows
  @Override
  public int run(String... args) {
//  public static void main(String[] args) {
    if (args.length == 0) {
      readEvalPrintLoop();
    } else {
      CommandLine commandLine = parseOptions(args);
      if (!commandLine.hasOption('s') || !commandLine.hasOption('o') || !commandLine.hasOption('l')) {
        System.out.println("Usage: larky-runer -s script_file -o output_file -l log_file -i input_param_file");
        return 1;
//        return;
      }
      execute(commandLine);
    }

    return 0;
  }

  @SneakyThrows
  private static void execute(CommandLine commandLine) {
    final File tempFile = File.createTempFile("merge", ".star");
    String outputPath = commandLine.getOptionValue('o');
    String script = readFile(commandLine.getOptionValue('s'));
    boolean debug = commandLine.hasOption("d");

    String input = commandLine.hasOption('i') ?
        readFile(commandLine.getOptionValue('i'))
        : "";

    String logPath = commandLine.hasOption('l') ?
        commandLine.getOptionValue('l')
        : "";

    PrependMergedStarFile prependMergedStarFile = new PrependMergedStarFile(input, script);

    Files.write(Path.of(tempFile.getPath()), prependMergedStarFile.readContentBytes(), StandardOpenOption.WRITE);
//    Files.write(Path.of("/tmp/file-merged.out"), prependMergedStarFile.readContentBytes(), StandardOpenOption.CREATE_NEW);

    if (debug) {
      int port = commandLine.hasOption("p") ? Integer.parseInt(commandLine.getOptionValue("p")) : 7300;
      Reporter reporter = new Reporter(new EventBus());
      StarlarkDebuggerModule.initializeDebugging(reporter, port, true);
//      System.err.println("==================================");
//      System.err.println(new String(prependMergedStarFile.readContentBytes()));
//      System.err.println("==================================");
    }

    Console console = new FileConsole(CapturingConsole.captureAllConsole(
        LogConsole.writeOnlyConsole(System.out, true)), Path.of(logPath), Duration.ZERO);

//    String output = new LarkyScript(StarlarkMode.STRICT)
//        .executeSkylarkWithOutput(prependMergedStarFile,
//            new ModuleSupplier().create(), console)
//        .toString();

    String output = new LarkyScript(StarlarkMode.STRICT)
        .executeSkylarkWithOutput(tempFile,
            new ModuleSupplier().create(), console)
        .toString();

    if (debug) {
      System.err.println(output);
      StarlarkDebuggerModule.disableDebugging();
    }

    try(FileWriter writer = new FileWriter(Path.of(outputPath).toFile())) {
        writer.write(output);
    }
    catch(IOException e) {
      e.printStackTrace(System.err);
      throw new RuntimeException(e.getMessage(), e);
    }
  }

  private static void readEvalPrintLoop() {
    System.err.println("Welcome to Starlark (java.starlark.net)");
    String line;

    // TODO(adonovan): parse a compound statement, like the Python and
    // go.starlark.net REPLs. This requires a new grammar production, and
    // integration with the lexer so that it consumes new
    // lines only until the parse is complete.

    while ((line = prompt()) != null) {
      ParserInput input = ParserInput.fromString(line, "<stdin>");
      try {
        Object result = Starlark.execFile(input, OPTIONS, module, thread);
        if (result != Starlark.NONE) {
          System.out.println(Starlark.repr(result));
        }
      } catch (SyntaxError.Exception ex) {
        for (SyntaxError error : ex.errors()) {
          System.err.println(error);
        }
      } catch (EvalException ex) {
        // TODO(adonovan): provide a SourceReader. Requires that we buffer the
        // entire history so that line numbers don't reset in each chunk.
        System.err.println(ex.getMessageWithStack());
      } catch (InterruptedException ex) {
        System.err.println("Interrupted");
      }
    }
  }

  private static String prompt() {
    StringBuilder input = new StringBuilder();
    System.out.print(START_PROMPT);
    try {
      String lineSeparator = "";
      while (true) {
        String line = reader.readLine();
        if (line == null) {
          return null;
        }
        if (line.isEmpty()) {
          return input.toString();
        }
        input.append(lineSeparator).append(line);
        lineSeparator = "\n";
        System.out.print(CONTINUATION_PROMPT);
      }
    } catch (IOException e) {
      System.err.format("Error reading line: %s\n", e);
      return null;
    }
  }

  private static CommandLine parseOptions(String... args) throws ParseException {
    Options options = new Options();
    options.addOption("s", "script", true, "Starlark script");
    options.addOption("i", "input", true, "Input parameters");
    options.addOption("o", "output", true, "Output parameters");
    options.addOption("l", "log", true, "Log output");
    options.addOption("d", "debug", false, "Verbose merged script");
    options.addOption("p", "debug-port", true, "Debug port");
    CommandLineParser parser = new DefaultParser();
    return parser.parse(options, args);
  }

  private static String readFile(String filePath) {
    try {
      return filePath.trim().isEmpty() ? "" : Files.readString(Paths.get(filePath));
    } catch (IOException e) {
      System.err.println("Input file path is incorrect!");
      return "";
    }
  }
}
