/*
 * Copyright (c) 2012, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
package jdk.jfr.internal.dcmd;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.internal.JVM;
import jdk.jfr.internal.LogLevel;
import jdk.jfr.internal.LogTag;
import jdk.jfr.internal.Logger;
import jdk.jfr.internal.OldObjectSample;
import jdk.jfr.internal.PlatformRecording;
import jdk.jfr.internal.PrivateAccess;
import jdk.jfr.internal.Type;
import jdk.jfr.internal.jfc.JFC;
import jdk.jfr.internal.jfc.model.JFCModel;
import jdk.jfr.internal.jfc.model.JFCModelException;
import jdk.jfr.internal.jfc.model.XmlInput;
import jdk.jfr.internal.query.Report;
import jdk.jfr.internal.util.Utils;

/**
 * JFR.start
 *
 */
//Instantiated by native
final class DCmdStart extends AbstractDCmd {

    @Override
    public void execute(ArgumentParser parser) throws DCmdException {
        String name = parser.getOption("name");
        List<String> list = parser.getOption("settings");
        String[] settings = null;
        if (list == null) {
            settings = new String[] {"default.jfc"};
        } else {
            settings = list.toArray(new String[0]);
        }
        if (settings.length == 1 && "none".equals(settings[0])) {
            settings = new String[0];
        }
        Long delay = parser.getOption("delay");
        Long duration = parser.getOption("duration");
        Boolean disk = parser.getOption("disk");
        String path = parser.getOption("filename");
        Long maxAge = parser.getOption("maxage");
        Long maxSize = parser.getOption("maxsize");
        Long flush = parser.getOption("flush-interval");
        Boolean dumpOnExit = parser.getOption("dumponexit");
        Boolean pathToGcRoots = parser.getOption("path-to-gc-roots");
        List<String> reports = parser.getOption("report-on-exit");

        if (name != null) {
            try {
                Integer.parseInt(name);
                throw new DCmdException("Name of recording can't be numeric");
            } catch (NumberFormatException nfe) {
                // ok, can't be mixed up with name
            }
        }

        if (duration == null && Boolean.FALSE.equals(dumpOnExit) && path != null) {
            throw new DCmdException("Filename can only be set for a time bound recording or if dumponexit=true. Set duration/dumponexit or omit filename.");
        }
        if (settings.length == 1 && settings[0].length() == 0) {
            throw new DCmdException("No settings specified. Use settings=none to start without any settings");
        }

        LinkedHashMap<String, String> s;
        if (parser.hasExtendedOptions()) {
           s = configureExtended(settings, parser);
        } else {
           s = configureStandard(settings);
        }

        OldObjectSample.updateSettingPathToGcRoots(s, pathToGcRoots);

        if (duration != null) {
            if (duration < 1000L * 1000L * 1000L) {
                // to avoid typo, duration below 1s makes no sense
                throw new DCmdException("Could not start recording, duration must be at least 1 second.");
            }
        }

        if (delay != null) {
            if (delay < 1000L * 1000L * 1000L) {
                // to avoid typo, delay shorter than 1s makes no sense.
                throw new DCmdException("Could not start recording, delay must be at least 1 second.");
            }
        }

        if (flush != null) {
            if (Boolean.FALSE.equals(disk)) {
                throw new DCmdException("Flush can only be set for recordings that are to disk.");
            }
        }

        if (!FlightRecorder.isInitialized() && delay == null) {
            initializeWithForcedInstrumentation(s);
        }

        Recording recording = new Recording();
        if (name != null) {
            recording.setName(name);
        }

        if (disk != null) {
            if (!disk) {
                if (maxAge != null) {
                    logWarning("Option maxage has no effect with disk=false.");
                }
                if (maxSize != null) {
                    logWarning("Option maxsize has no effect with disk=false.");
                }
            }
            recording.setToDisk(disk.booleanValue());
        }

        recording.setSettings(s);
        Path dumpPath = null;

        // Generate dump filename if user has specified a time-bound recording
        if (duration != null && path == null) {
            path = resolvePath(recording, null).toString();
        }
        PlatformRecording pr = PrivateAccess.getInstance().getPlatformRecording(recording);

        if (path != null) {
            try {
                if (dumpOnExit == null) {
                    // default to dumponexit=true if user specified filename
                    dumpOnExit = Boolean.TRUE;
                }
                Path p = Paths.get(path);
                if (Files.isDirectory(p)) {
                    // Decide destination filename at dump time
                    // Purposely avoid generating filename in Recording#setDestination due to
                    // security concerns
                    pr.setDumpDirectory(p);
                } else {
                    dumpPath = resolvePath(recording, path);
                    recording.setDestination(dumpPath);
                }
            } catch (IOException | InvalidPathException e) {
                recording.close();
                throw new DCmdException("Could not start recording, not able to write to file %s. %s ", path, e.getMessage());
            }
        }

        if (maxAge != null) {
            recording.setMaxAge(Duration.ofNanos(maxAge));
        }

        if (flush != null) {
            pr.setFlushInterval(Duration.ofNanos(flush));
        }

        if (maxSize != null) {
            recording.setMaxSize(maxSize);
        }

        if (duration != null) {
            recording.setDuration(Duration.ofNanos(duration));
        }

        if (dumpOnExit != null) {
            recording.setDumpOnExit(dumpOnExit);
        }

        if (reports != null) {
            addReports(pr, reports);
        }

        if (delay != null) {
            Duration dDelay = Duration.ofNanos(delay);
            recording.scheduleStart(dDelay);
            print("Recording " + recording.getId() + " scheduled to start in ");
            printTimespan(dDelay, " ");
            print(".");
        } else {
            recording.start();
            print("Started recording " + recording.getId() + ".");
        }

        if (recording.isToDisk() && duration == null && maxAge == null && maxSize == null) {
            print(" No limit specified, using maxsize=250MB as default.");
            recording.setMaxSize(250*1024L*1024L);
        }

        if (dumpPath != null && duration != null) {
            println(" The result will be written to:");
            println();
            printPath(dumpPath);
        } else {
            println();
            println();
            String cmd = duration == null ? "dump" : "stop";
            String fileOption = path == null ? "filename=FILEPATH " : "";
            String recordingspecifier = "name=" + recording.getId();
            // if user supplied a name, use it.
            if (name != null) {
                recordingspecifier = "name=" + quoteIfNeeded(name);
            }
            print("Use jcmd " + getPid() + " JFR." + cmd + " " + recordingspecifier + " " + fileOption + "to copy recording data to file.");
            println();
        }
    }

    // Add report-on-exit for -XX:StartFlightRecording
    @Override
    protected Argument[] getParseArguments(String source) {
        Argument[] argumentInfo = getArgumentInfos();;
        if (!"internal".equals(source)) {
            return argumentInfo;
        }
        Argument[] newArray = Arrays.copyOf(argumentInfo, argumentInfo.length + 1);
        newArray[argumentInfo.length] = new Argument("report-on-exit",
                "Display views on exit. See 'jfr help view' for available views to report.",
                "STRING SET", false, true, null, true);
        return newArray;
    }

    private LinkedHashMap<String, String> configureStandard(String[] settings) throws DCmdException {
        LinkedHashMap<String, String> s = LinkedHashMap.newLinkedHashMap(settings.length);
        for (String configName : settings) {
            try {
                s.putAll(JFC.createKnown(configName).getSettings());
            } catch (InvalidPathException | IOException | ParseException e) {
                throw new DCmdException(JFC.formatException("Could not", e, configName), e);
            }
        }
        return s;
    }

    private void addReports(PlatformRecording recording, List<String> reportNames) throws DCmdException {
        if (!recording.isToDisk()) {
            throw new DCmdException("Option report-on-exit can only be used when recording to disk.");
        }
        Map<String, Report> reportLookup = new HashMap<>();
        for (Report report : Report.getReports()) {
            reportLookup.put(report.name(), report);
        }
        for (String name : reportNames) {
            Report report = reportLookup.get(name);
            if (report != null) {
                recording.addReport(report);
            } else {
                throw new DCmdException("Unknown view '" + name + "' specified for report-on-exit. Use 'jfr help view' to see a list of available views.");
            }
        }
    }

    private LinkedHashMap<String, String> configureExtended(String[] settings, ArgumentParser parser) throws DCmdException {
        JFCModel model = new JFCModel(l -> logWarning(l));
        for (String setting : settings) {
            try {
                model.parse(JFC.ofPath(setting));
            } catch (InvalidPathException | IOException | JFCModelException | ParseException e) {
                throw new DCmdException(JFC.formatException("Could not", e, setting), e);
            }
        }
        try {
            Set<String> jfcOptions = new HashSet<>();
            for (XmlInput input : model.getInputs()) {
                jfcOptions.add(input.getName());
            }
            parser.checkSpelling(jfcOptions);
            Map<String, String> jfcSettings = model.getSettings();
            for (var entry : parser.getExtendedOptions().entrySet()) {
                String value = (String)entry.getValue();
                String optionName = entry.getKey();
                boolean added = optionName.startsWith("+");
                if (!added && !jfcOptions.contains(optionName) && !jfcSettings.containsKey(optionName)) {
                    // Option/setting doesn't exist and it is not a spelling error.
                    // By not throwing an exception, and instead print a warning,
                    // it is easier migrate to a newer JDK version without
                    // breaking startup scripts
                     logWarning("The .jfc option/setting '" + optionName + "' doesn't exist.");
                } else {
                    model.configure(entry.getKey(), value);
                }
            }
            return model.getSettings();
        } catch (IllegalArgumentException iae) {
             throw new DCmdException(iae.getMessage()); // spelling error, invalid value
        }
    }

    // Instruments JDK-events on class load to reduce startup time
    private void initializeWithForcedInstrumentation(Map<String, String> settings) {
        if (!hasJDKEvents(settings)) {
            return;
        }
        try {
            JVM.setForceInstrumentation(true);
            FlightRecorder.getFlightRecorder();
        } finally {
            JVM.setForceInstrumentation(false);
        }
    }

    private boolean hasJDKEvents(Map<String, String> settings) {
        String[] eventNames = new String[7];
        eventNames[0] = "FileRead";
        eventNames[1] = "FileWrite";
        eventNames[2] = "SocketRead";
        eventNames[3] = "SocketWrite";
        eventNames[4] = "JavaErrorThrow";
        eventNames[5] = "JavaExceptionThrow";
        eventNames[6] = "FileForce";
        for (String eventName : eventNames) {
            if ("true".equals(settings.get(Type.EVENT_NAME_PREFIX + eventName + "#enabled"))) {
                return true;
            }
        }
        return false;
    }

    public String[] getStartupHelp() {
        Map<String, String> parameters = Map.of(
            "$SYNTAX", "-XX:StartFlightRecording:[options]",
            "$SOURCE_NO_ARGUMENTS", "-XX:StartFlightRecording",
            "$SOURCE", "-XX:StartFlightRecording:",
            "$DELIMITER", ",",
            "$DELIMITER_NAME", "comma",
            "$DIRECTORY", exampleDirectory(),
            "$REPORT_ON_EXIT", reportOnExit(),
            "$JFC_OPTIONS", jfcOptions()
        );
        return Utils.format(helpTemplate(), parameters).lines().toArray(String[]::new);
    }

    @Override
    public String[] getHelp() {
        Map<String, String> parameters = Map.of(
           "$SYNTAX", "JFR.start [options]",
           "$SOURCE_NO_ARGUMENTS", "$ jcmd <pid> JFR.start",
           "$SOURCE", "$ jcmd <pid> JFR.start ",
           "$DELIMITER", " ",
           "$DELIMITER_NAME", "whitespace",
           "$DIRECTORY", exampleDirectory(),
           "$REPORT_ON_EXIT", "",
           "$JFC_OPTIONS", jfcOptions()
        );
        return Utils.format(helpTemplate(), parameters).lines().toArray(String[]::new);
    }

    private static String helpTemplate() {
            // 0123456789001234567890012345678900123456789001234567890012345678900123456789001234567890
        return """
               Syntax : $SYNTAX

               Options:

                 delay            (Optional) Length of time to wait before starting to record
                                  (INT followed by 's' for seconds 'm' for minutes or h' for
                                  hours, 0s)

                 disk             (Optional) Flag for also writing the data to disk while recording
                                  (BOOLEAN, true)

                 dumponexit       (Optional) Flag for writing the recording to disk when the Java
                                  Virtual Machine (JVM) shuts down. If set to 'true' and no value
                                  is given for filename, the recording is written to a file in the
                                  directory where the process was started. The file name is a
                                  system-generated name that contains the process ID, the recording
                                  ID and the current time stamp. (For example:
                                  id-1-2021_09_14_09_00.jfr) (BOOLEAN, false)

                 duration         (Optional) Length of time to record. Note that 0s means forever
                                  (INT followed by 's' for seconds 'm' for minutes or 'h' for
                                  hours, 0s)

                 filename         (Optional) Name of the file to which the flight recording data is
                                  written when the recording is stopped. If no filename is given, a
                                  filename is generated from the PID and the current date and is
                                  placed in the directory where the process was started. The
                                  filename may also be a directory in which case, the filename is
                                  generated from the PID and the current date in the specified
                                  directory. (FILE, no default value)

                                  Note: If a filename is given, '%p' in the filename will be
                                  replaced by the PID, and '%t' will be replaced by the time in
                                  'yyyy_MM_dd_HH_mm_ss' format.

                 maxage           (Optional) Maximum time to keep the recorded data on disk. This
                                  parameter is valid only when the disk parameter is set to true.
                                  Note 0s means forever. (INT followed by 's' for seconds 'm'
                                  for minutes or 'h' for hours, 0s)

                 maxsize          (Optional) Maximum size of the data to keep on disk in bytes if
                                  one of the following suffixes is not used: 'm' or 'M' for
                                  megabytes OR 'g' or 'G' for gigabytes. This parameter is valid
                                  only when the disk parameter is set to 'true'. The value must not
                                  be less than the value for the maxchunksize parameter set with
                                  the JFR.configure command. (STRING, 0 (no max size))

                 name             (Optional) Name of the recording. If no name is provided, a name
                                  is generated. Make note of the generated name that is shown in
                                  the response to the command so that you can use it with other
                                  commands. (STRING, system-generated default name)

                 path-to-gc-roots (Optional) Flag for saving the path to garbage collection (GC)
                                  roots at the end of a recording. The path information is useful
                                  for finding memory leaks but collecting it is time consuming.
                                  Turn on this flag only when you have an application that you
                                  suspect has a memory leak. If the settings parameter is set to
                                  'profile', then the information collected includes the stack
                                  trace from where the potential leaking object was allocated.
                                  (BOOLEAN, false)
                 $REPORT_ON_EXIT
                 settings         (Optional) Name of the settings file that identifies which events
                                  to record. To specify more than one file, use the settings
                                  parameter repeatedly. Include the path if the file is not in
                                  JAVA-HOME/lib/jfr. The following profiles are included with the
                                  JDK in the JAVA-HOME/lib/jfr directory: 'default.jfc': collects a
                                  predefined set of information with low overhead, so it has minimal
                                  impact on performance and can be used with recordings that run
                                  continuously; 'profile.jfc': Provides more data than the
                                  'default.jfc' profile, but with more overhead and impact on
                                  performance. Use this configuration for short periods of time
                                  when more information is needed. Use none to start a recording
                                  without a predefined configuration file. (STRING,
                                  JAVA-HOME/lib/jfr/default.jfc)

               Event settings and .jfc options can also be specified using the following syntax:

                 jfc-option=value    (Optional) The option value to modify. To see available
                                     options for a .jfc file, use the 'jfr configure' command.

                 event-setting=value (Optional) The event setting value to modify. Use the form:
                                     <event-name>#<setting-name>=<value>
                                     To add a new event setting, prefix the event name with '+'.

               In case of a conflict between a parameter and a .jfc option, the parameter will
               take  precedence. The whitespace character can be omitted for timespan values,
               i.e. 20s. For more information about the settings syntax, see Javadoc of the
               jdk.jfr package.
               $JFC_OPTIONS
               Options must be specified using the <key> or <key>=<value> syntax. Multiple
               options are separated with a $DELIMITER_NAME.

               Example usage:

                $SOURCE_NO_ARGUMENTS
                $SOURCEfilename=dump.jfr
                $SOURCEfilename=$DIRECTORY
                $SOURCEdumponexit=true
                $SOURCEmaxage=1h$DELIMITERmaxsize=1000M
                $SOURCEsettings=profile
                $SOURCEdelay=5m$DELIMITERsettings=my.jfc
                $SOURCEgc=high$DELIMITERmethod-profiling=high
                $SOURCEjdk.JavaMonitorEnter#threshold=1ms
                $SOURCE+HelloWorld#enabled=true$DELIMITER+HelloWorld#stackTrace=true
                $SOURCEsettings=user.jfc$DELIMITERcom.example.UserDefined#enabled=true
                $SOURCEsettings=none$DELIMITER+Hello#enabled=true

               Note, if the default event settings are modified, overhead may exceed 1%.

               """;
    }

    private static String reportOnExit() {
        return
        """

          report-on-exit   Specifies the name of the view to display when the Java Virtual
                           Machine (JVM) shuts down. To specify more than one view, use
                           the `report-on-exit` parameter repeatedly, for each view. This
                           option is not available if the disk option is set to false.
                           For a list of available views, see `jfr help view`. By default,
                           no report is generated.
        """;
    }

    private static String jfcOptions() {
        try {
            StringBuilder sb = new StringBuilder();
            for (Path s : JFC.getPredefined()) {
                String name = JFC.nameFromPath(s);
                JFCModel model = JFCModel.create(s, l -> {});
                sb.append('\n');
                sb.append("Options for ").append(name).append(":\n");
                sb.append('\n');
                for (XmlInput input : model.getInputs()) {
                    sb.append("  ").append(input.getOptionSyntax()).append('\n');
                    sb.append('\n');
                }
            }
            return sb.toString();
        } catch (IOException | JFCModelException | ParseException  e) {
            Logger.log(LogTag.JFR_DCMD, LogLevel.DEBUG, "Could not list .jfc options for JFR.start. " + e.getMessage());
            return "";
        }
    }

    @Override
    public Argument[] getArgumentInfos() {
        return new Argument[] {
            new Argument("name",
                "Name that can be used to identify recording, e.g. \\\"My Recording\\\"",
                "STRING", false, true, null, false),
            new Argument("settings",
                "Settings file(s), e.g. profile or default. See JAVA_HOME/lib/jfr",
                "STRING SET", false, true, "default.jfc", true),
            new Argument("delay",
                "Delay recording start with (s)econds, (m)inutes), (h)ours), or (d)ays, e.g. 5h.",
                "NANOTIME", false, true, "0s", false),
            new Argument("duration",
                "Duration of recording in (s)econds, (m)inutes, (h)ours, or (d)ays, e.g. 300s.",
                "NANOTIME", false, true, null, false),
            new Argument("disk",
                "Recording should be persisted to disk",
                "BOOLEAN", false, true, "true", false),
            new Argument("filename",
                "Resulting recording filename, e.g. \\\"" + exampleFilename() +  "\\\"",
                "FILE", false, true, "hotspot-pid-xxxxx-id-y-YYYY_MM_dd_HH_mm_ss.jfr", false),
            new Argument("maxage",
                "Maximum time to keep recorded data (on disk) in (s)econds, (m)inutes, (h)ours, or (d)ays, e.g. 60m, or 0 for no limit",
                "NANOTIME", false, true, "0", false),
            new Argument("maxsize",
                "Maximum amount of bytes to keep (on disk) in (k)B, (M)B or (G)B, e.g. 500M, or 0 for no limit",
                "MEMORY SIZE", false, true, "250M", false),
            new Argument("flush-interval",
                "Minimum time before flushing buffers, measured in (s)econds, e.g. 4 s, or 0 for flushing when a recording ends",
                "NANOTIME", false, true, "1s", false),
            new Argument("dumponexit",
                "Dump running recording when JVM shuts down",
                "BOOLEAN", false, true, "false", false),
            new Argument("path-to-gc-roots",
                "Collect path to GC roots",
                "BOOLEAN", false, true, "false", false)
        };
    }
}
