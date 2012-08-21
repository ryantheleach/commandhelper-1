package com.laytonsmith.core;

import com.laytonsmith.PureUtilities.DateUtil;
import com.laytonsmith.PureUtilities.ExecutionQueue;
import com.laytonsmith.PureUtilities.FileUtility;
import com.laytonsmith.PureUtilities.Preferences;
import com.laytonsmith.PureUtilities.Preferences.Preference;
import java.io.File;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EmptyStackException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * TODO: The following points need profile hooks: 1 - Alias run times 1 - Event
 * run times 1 - Execution Queue task run times 1 - set_timeout() closure run
 * times 1 - set_interval() closure run times 2 - for() run times (with
 * parameters) 2 - foreach() run times (with parameters) 2 - while() run times
 * (with parameters) 2 - dowhile() run times (with parameters) 3 - Procedure
 * execution run times (with parameters) 4 - read() run times 4 - get_value()
 * run times 4 - get_values() run times 4 - store_value() run times 4 -
 * clear_value() run times 4 - has_value() run times 5 - All functions run times
 * 5 - Compilation run time
 */
/**
 *
 * @author lsmith
 */
public final class Profiler {

	public static void Install(File initFile) throws IOException {
		//We just want to create the config file initially
		GetPrefs(initFile);
	}

	public static Preferences GetPrefs(File initFile) throws IOException {
		List<Preference> defaults = new ArrayList<Preference>(Arrays.asList(new Preference[]{
					new Preference("profiler-on", "false", Preferences.Type.BOOLEAN, "Turns the profiler on or off. The profiler can cause a slight amount of lag, so generally speaking"
					+ " you don't leave it on during normal operation."),
					new Preference("profiler-granularity", "1", Preferences.Type.INT, "Sets the granularity of the profiler. 1 logs some things, while 5 logs everything possible."),
					new Preference("profiler-log", "%Y-%M-%D-profiler.log", Preferences.Type.STRING, "The location of the profiler output log. The following macros are supported"
					+ " and will expand to the specified values: %Y - Year, %M - Month, %D - Day, %h - Hour, %m - Minute, %s - Second"),
					new Preference("write-to-file", "true", Preferences.Type.BOOLEAN, "If true, will write results out to file."),
					new Preference("write-to-screen", "false", Preferences.Type.BOOLEAN, "If true, will write results out to screen."),}));
		Preferences prefs = new Preferences("CommandHelper", Static.getLogger(), defaults, "These settings control the integrated profiler");
		prefs.init(initFile);
		return prefs;
	}
	private Map<ProfilePoint, Long> operations;
	private LogLevel configGranularity;
	private boolean profilerOn;
	private String logFile;
	private boolean writeToFile;
	private boolean writeToScreen;
	private Preferences prefs;
	private long queuedProfilePoints = 0;
	//To prevent file fights across threads, we only want one outputQueue.
	private static ExecutionQueue outputQueue;
	private final static ProfilePoint NULL_OP = new ProfilePoint("NULL_OP");

	public Profiler(File initFile) throws IOException {
		prefs = GetPrefs(initFile);
		//We want speed here, not memory usage, so lets put an excessively large capacity, and excessively low load factor
		operations = new HashMap<ProfilePoint, Long>(1024, 0.25f);

		configGranularity = LogLevel.getEnum((Integer) prefs.getPreference("profiler-granularity"));
		if (configGranularity == null) {
			configGranularity = LogLevel.ERROR;
		}
		profilerOn = (Boolean) prefs.getPreference("profiler-on");
		logFile = (String) prefs.getPreference("profiler-log");
		writeToFile = (Boolean) prefs.getPreference("write-to-file");
		writeToScreen = (Boolean) prefs.getPreference("write-to-screen");
		if (outputQueue == null) {
			outputQueue = new ExecutionQueue("CommandHelper-Profiler", "default");
		}
		new GarbageCollectionDetector();
		//As a form of calibration, we want to "warm up" a point.
		//For whatever reason, this levels out the profile points pretty well.
		ProfilePoint warmupPoint = this.start("Warming up the profiler", LogLevel.VERBOSE);
		this.stop(warmupPoint);
	}

	/**
	 * Starts a timer, and returns a profile point object, which should be used
	 * to stop this timer later. A special ProfilePoint is returned if this
	 * profile point shouldn't be logged based on the granularity settings,
	 * which short circuits the entire profiling process, for non-trigger
	 * points, which should speed operation considerably.
	 *
	 * @param name The name to be used during logging
	 * @param granularity
	 * @return
	 */
	public ProfilePoint start(String name, LogLevel granularity) {
		if (!isLoggable(granularity)) {
			return NULL_OP;
		}
		ProfilePoint p = new ProfilePoint(name);
		start0(p, granularity);
		return p;
	}

	/**
	 * "Starts" an operation. Note that for each start, you must use EXACTLY one
	 * stop, with exactly the same object for operationName. Multiple profile
	 * points can share the same name, and they will be stacked and lined up
	 * accordingly.
	 *
	 * @param operationName The name of the operation. A corresponding call to
	 * DoStop must be called with this exact same object.
	 * @param granularity The granularity at which to log.
	 */
	private void start0(ProfilePoint operationName, LogLevel granularity) {
		if (operations.containsKey(operationName)) {
			//Nope. Can't queue up multiple versions of the same
			//id
			throw new RuntimeException("Cannot queue the same profile point multiple times!");
		}
		queuedProfilePoints++;
		operations.put(operationName, System.nanoTime());
	}

	private final static String gcString = " (however, the garbage collector was run during this profile point)";
	public void stop(ProfilePoint operationName) {
		long stop = System.nanoTime();
		if (operationName == NULL_OP) {
			return;
		}
		if (!operations.containsKey(operationName)) {
			return;
		}
		long total = stop - operations.get(operationName);
		//1 million nano seconds in 1 ms. We want x.xxx ms shown, so divide by 1000, round (well, integer truncate, since it's faster), then divide by 1000 again.
		//voila, significant figure to the 3rd degree.
		double time = (total / 1000) / 1000.0;
		doLog(operationName.toString() + " took a total of " + time + "ms" + (operationName.wasGCd()?gcString:""));
		queuedProfilePoints--;
	}

	public boolean isLoggable(LogLevel granularity) {
		if (!profilerOn || granularity == null) {
			return false;
		}
		return granularity.getLevel() <= configGranularity.getLevel();
	}

	/**
	 * Pushes a log to either the screen or the log file, depending on config
	 * settings. Arbitrary messages can be logged using this method.
	 *
	 * @param message
	 */
	public void doLog(final String message) {
		outputQueue.push(null, new Runnable() {
			public void run() {
				if (writeToScreen) {
					System.out.println(message);
				}
				if (writeToFile) {
					try {
						FileUtility.write(DateUtil.ParseCalendarNotation("%Y-%M-%D %h:%m.%s") + ": " + message + Static.LF(), //Message to log
								new File(DateUtil.ParseCalendarNotation(logFile)), //File to output to
								FileUtility.APPEND, //We want to append
								true); //Create it for us if it doesn't exist
					} catch (IOException ex) {
						System.err.println("While trying to write to the profiler log file, recieved an IOException: " + ex.getMessage());
					}
				}

			}
		});
	}

	private final class GarbageCollectionDetector {

		@Override
		protected void finalize() throws Throwable {
			if (queuedProfilePoints > 0) {
				for (ProfilePoint p : operations.keySet()) {
					p.garbageCollectorRun();
				}
			}
			new GarbageCollectionDetector();
		}
	}

	public static class ProfilePoint implements Comparable<ProfilePoint> {

		private String name;
		boolean GCRun;

		public ProfilePoint(String name) {
			this.name = name;
			GCRun = false;
		}

		@Override
		public String toString() {
			return name;
		}

		void garbageCollectorRun() {
			GCRun = true;
		}

		boolean wasGCd() {
			return GCRun;
		}

		/**
		 * This is an arbitrary comparison, for the sake of fast tree searches.
		 *
		 * @param o
		 * @return
		 */
		public int compareTo(ProfilePoint o) {
			return o.name.compareTo(name);
		}
	}
}