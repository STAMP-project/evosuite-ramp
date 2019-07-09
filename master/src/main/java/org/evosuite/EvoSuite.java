/**
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
/**
 *
 */
package org.evosuite;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.SystemUtils;
import org.evosuite.Properties.Criterion;
import org.evosuite.Properties.SecondaryObjectiveVotingMethod;
import org.evosuite.classpath.ClassPathHacker;
import org.evosuite.executionmode.Continuous;
import org.evosuite.executionmode.Help;
import org.evosuite.executionmode.ListClasses;
import org.evosuite.executionmode.WriteDependencies;
import org.evosuite.executionmode.ListParameters;
import org.evosuite.executionmode.MeasureCoverage;
import org.evosuite.executionmode.PrintStats;
import org.evosuite.executionmode.Setup;
import org.evosuite.executionmode.TestGeneration;
import org.evosuite.junit.writer.TestSuiteWriterUtils;
import org.evosuite.runtime.sandbox.MSecurityManager;
import org.evosuite.runtime.util.JavaExecCmdUtil;
import org.evosuite.setup.InheritanceTree;
import org.evosuite.setup.InheritanceTreeGenerator;
import org.evosuite.utils.LoggingUtils;
import org.evosuite.utils.Randomness;
import org.evosuite.utils.SpawnProcessKeepAliveChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>
 * EvoSuite class.
 * </p>
 *
 * @author Gordon Fraser
 */
public class EvoSuite {

    static {
        LoggingUtils.loadLogbackForEvoSuite();
    }

    private static Logger logger = LoggerFactory.getLogger(EvoSuite.class);

    private static String separator = System.getProperty("file.separator");
    //private static String javaHome = System.getProperty("java.home");

    /**
     * Functional moved to @{@link JavaExecCmdUtil#getJavaBinExecutablePath()}
     * Constant
     * <code>JAVA_CMD="javaHome + separator + bin + separatorj"{trunked}</code>
     */
    //public final static String JAVA_CMD = javaHome + separator + "bin" + separator + "java";

    public static String base_dir_path = System.getProperty("user.dir");

    public static String generateInheritanceTree(String cp) throws IOException {
        LoggingUtils.getEvoLogger().info("* Analyzing classpath (generating inheritance tree)");
        List<String> cpList = Arrays.asList(cp.split(File.pathSeparator));
        // Clear current inheritance file to make sure a new one is generated
        Properties.INHERITANCE_FILE = "";
        InheritanceTree tree = InheritanceTreeGenerator.createFromClassPath(cpList);
        File outputFile = File.createTempFile("ES_inheritancetree", ".xml.gz");
        outputFile.deleteOnExit();
        InheritanceTreeGenerator.writeInheritanceTree(tree, outputFile);
        return outputFile.getAbsolutePath();
    }

    private void setupProperties() {
        if (base_dir_path.equals("")) {
            Properties.getInstanceSilent();
        } else {
            Properties.getInstanceSilent().loadProperties(base_dir_path
                            + separator
                            + Properties.PROPERTIES_FILE,
                    true);
        }
    }

    /**
     * <p>
     * parseCommandLine
     * </p>
     *
     * @param args an array of {@link java.lang.String} objects.
     * @return a {@link java.lang.Object} object.
     */
    public Object parseCommandLine(String[] args) {
        Options options = CommandLineParameters.getCommandLineOptions();

        List<String> javaOpts = new ArrayList<String>();

        String version = EvoSuite.class.getPackage().getImplementationVersion();
        if (version == null) {
            version = "";
        }


        // create the parser
        CommandLineParser parser = new GnuParser();
        try {
            // parse the command line arguments
            CommandLine line = parser.parse(options, args);

            if (!line.hasOption(Setup.NAME)) {
                /*
				 * -setup is treated specially because it uses the extra input arguments
				 * 
				 * TODO: Setup should be refactored/fixed
				 */
                String[] unrecognized = line.getArgs();
                if (unrecognized.length > 0) {
                    String msg = "";
                    if(unrecognized.length==1){
                        msg = "There is one unrecognized input:";
                    } else {
                        msg = "There are " + unrecognized.length + " unrecognized inputs:";
                    }
                    msg += " " + Arrays.toString(unrecognized);
                    msg += "\nRecall, '-Dx=v' assignments should have no space, i.e. '-Dx= v' and '-Dx = v' are wrong";
                    throw new IllegalArgumentException(msg);
                }
            }

            setupProperties();

            if (SystemUtils.IS_JAVA_9 || SystemUtils.IS_JAVA_10) {
                throw new RuntimeException(Properties.JAVA_VERSION_WARN_MSG);
            }

            if (TestSuiteWriterUtils.needToUseAgent() && Properties.JUNIT_CHECK) {
                ClassPathHacker.initializeToolJar();
            }

            if (!line.hasOption("regressionSuite")) {
                if (line.hasOption("criterion")) {
                    //TODO should check if already defined
                    javaOpts.add("-Dcriterion=" + line.getOptionValue("criterion"));

                    //FIXME should really better handle the validation of javaOpts in the master, not client
                    try {
                        Properties.getInstance().setValue("criterion", line.getOptionValue("criterion"));
                    } catch (Exception e) {
                        throw new Error("Invalid value for criterion: "+e.getMessage());
                    }
                }
            } else {
                javaOpts.add("-Dcriterion=regression");
            }

            if (line.hasOption("parallel")) {
                String[] values = line.getOptionValues("parallel");

                if (values.length != 3) {
                    throw new Error("Invalid amount of arguments for parallel");
                }

                javaOpts.add("-Dnum_parallel_clients=" + values[0]);
                javaOpts.add("-Dmigrants_iteration_frequency=" + values[1]);
                javaOpts.add("-Dmigrants_communication_rate=" + values[2]);

                try {
                    Properties.getInstance().setValue("num_parallel_clients", values[0]);
                    Properties.getInstance().setValue("migrants_iteration_frequency", values[1]);
                    Properties.getInstance().setValue("migrants_communication_rate", values[2]);
                } catch (Properties.NoSuchParameterException | IllegalAccessException e) {
                    throw new Error("Invalid values for parallel: " + e.getMessage());
                }
            } else {
                // Just to be save
                javaOpts.add("-Dnum_parallel_clients=" + 1);

                try {
                    Properties.getInstance().setValue("num_parallel_clients", 1);
                } catch (Properties.NoSuchParameterException | IllegalAccessException e) {
                    throw new Error("Could not set value: " + e.getMessage());
                }
            }

			/*
			 * FIXME: every time in the Master we set a parameter with -D,
			 * we should check if it actually exists (ie detect typos)
			 */

            CommandLineParameters.handleSeed(javaOpts, line);

            CommandLineParameters.addJavaDOptions(javaOpts, line);

//            if (TestSuiteWriterUtils.needToUseAgent() && Properties.JUNIT_CHECK) {
//                ClassPathHacker.initializeToolJar();
//            }

            CommandLineParameters.handleClassPath(line);

            CommandLineParameters.handleJVMOptions(javaOpts, line);


            if (line.hasOption("base_dir")) {
                base_dir_path = line.getOptionValue("base_dir");
                File baseDir = new File(base_dir_path);
                if (!baseDir.exists()) {
                    LoggingUtils.getEvoLogger().error("Base directory does not exist: "
                            + base_dir_path);
                    return null;
                }
                if (!baseDir.isDirectory()) {
                    LoggingUtils.getEvoLogger().error("Specified base directory is not a directory: "
                            + base_dir_path);
                    return null;
                }
            }

            CommandLineParameters.validateInputOptionsAndParameters(line);

			/*
			 * We shouldn't print when -listClasses, as we do not want to have
			 * side effects (eg, important when using it in shell scripts)
			 */
            if (!line.hasOption(ListClasses.NAME)) {

                LoggingUtils.getEvoLogger().info("* EvoSuite " + version);

                String conf = Properties.CONFIGURATION_ID;
                if (conf != null && !conf.isEmpty()) {
					/*
					 * This is useful for debugging on cluster
					 */
                    LoggingUtils.getEvoLogger().info("* Configuration: " + conf);
                }
            }


            if(Properties.CLIENT_ON_THREAD){
                MSecurityManager.setRunningClientOnThread(true);
            }

            if(Properties.SPAWN_PROCESS_MANAGER_PORT != null){
                SpawnProcessKeepAliveChecker.getInstance().registerToRemoteServerAndDieIfFails(
                        Properties.SPAWN_PROCESS_MANAGER_PORT
                );
            }

			/*
			 * Following "options" are the actual (mutually exclusive) execution modes of EvoSuite
			 */

            if (line.hasOption(Help.NAME)) {
                return Help.execute(options);
            }

            if (line.hasOption(Setup.NAME)) {
                return Setup.execute(javaOpts, line);
            }

            if (line.hasOption(MeasureCoverage.NAME)) {
                return MeasureCoverage.execute(options, javaOpts, line);
            }

            if (line.hasOption(ListClasses.NAME)) {
                return ListClasses.execute(options, line);
            }

            if (line.hasOption(WriteDependencies.NAME)) {
                return WriteDependencies.execute(options, javaOpts, line);
            }

            if (line.hasOption(PrintStats.NAME)) {
                return PrintStats.execute(options, javaOpts, line);
            }

            if (line.hasOption(ListParameters.NAME)) {
                return ListParameters.execute();
            }

            if (line.hasOption(Continuous.NAME)) {
                return Continuous.execute(options, javaOpts, line);
            }

            // Check if the execution count file exists if a path for it has been provided.
            // If the file does not exist, it is considered as invalid command line input.
            if (Properties.EXE_COUNT_FILE != null) {
                File executionCountFile = new File(Properties.EXE_COUNT_FILE);
                logger.info("Checking if execution count file exists: " + executionCountFile
                    .getAbsolutePath());
                if (!executionCountFile.exists()) {
                    throw new ParseException("Specified execution count file does not exist");
                }
                LoggingUtils.getEvoLogger()
                    .info("* " + ClientProcess.getPrettyPrintIdentifier()
                        + "Using execution count file: " + executionCountFile.getAbsolutePath());
            }

            if (Properties.SECONDARY_OBJECTIVE_VOTING == SecondaryObjectiveVotingMethod.WEIGHTED
                && Properties.SECONDARY_OBJECTIVE_WEIGHTS == null) {
                logger.warn(
                    "Property SECONDARY_OBJECTIVE_VOTING is set to WEIGHTED, but WEIGHTS are " +
                        "not set explicitly. Default weights of 1 will be used for " +
                        "all secondary objectives.");
            }

            return TestGeneration.executeTestGeneration(options, javaOpts, line);

        } catch (ParseException exp) {
            // oops, something went wrong
            logger.error("Parsing failed.  Reason: " + exp.getMessage());
            // automatically generate the help statement
            Help.execute(options);
        }

        return null;
    }

    public static boolean hasLegacyTargets() {
        File directory = new File(Properties.OUTPUT_DIR);
        if (!directory.exists()) {
            return false;
        }
        String[] extensions = {"task"};
        return !FileUtils.listFiles(directory, extensions, false).isEmpty();
    }

    /**
     * <p>
     * main
     * </p>
     *
     * @param args an array of {@link java.lang.String} objects.
     */
    public static void main(String[] args) {

        try {
            EvoSuite evosuite = new EvoSuite();
            evosuite.parseCommandLine(args);
        } catch (Throwable t) {
            logger.error("Fatal crash on main EvoSuite process. Class "
                    + Properties.TARGET_CLASS + " using seed " + Randomness.getSeed()
                    + ". Configuration id : " + Properties.CONFIGURATION_ID, t);
            System.exit(-1);
        }

		/*
		 * Some threads could still be running, so we need to kill the process explicitly
		 */
        System.exit(0);
    }

}
