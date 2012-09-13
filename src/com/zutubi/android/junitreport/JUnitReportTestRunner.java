/*
 * Copyright (C) 2010-2012 Zutubi Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.zutubi.android.junitreport;

import android.os.Bundle;
import android.test.AndroidTestRunner;
import android.test.InstrumentationTestRunner;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.Charset;

/**
 * Custom test runner that adds a {@link JUnitReportListener} to the underlying
 * test runner in order to capture test results in an XML report. You may use
 * this class in place of {@link InstrumentationTestRunner} in your test
 * project's manifest, and/or specify it to your Ant build using the test.runner
 * property.
 * <p/>
 * This runner behaves identically to the default, with the added side-effect of
 * producing JUnit XML reports. The report format is similar to that produced
 * by the Ant JUnit task's XML formatter, making it compatible with existing
 * tools that can process that format. See {@link JUnitReportListener} for
 * further details.
 * <p/>
 * This runner accepts arguments specified by the ARG_* constants.  For details
 * refer to the README.
 */
public class JUnitReportTestRunner extends InstrumentationTestRunner {
    /**
     * Name of the report file(s) to write, may contain __suite__ in multiFile mode.
     */
    private static final String ARG_REPORT_FILE = "reportFile";
    /**
     * If specified, path of the directory to write report files to.  May start with __external__.
     * If not set files are written to the internal storage directory of the app under test.
     */
    private static final String ARG_REPORT_DIR = "reportDir";
    /**
     * If true, stack traces in the report will be filtered to remove common noise (e.g. framework
     * methods).
     */
    private static final String ARG_FILTER_TRACES = "filterTraces";
    /**
     * If true, produce a separate file for each test suite.  By default a single report is created
     * for all suites.
     */
    private static final String ARG_MULTI_FILE = "multiFile";
    /**
     * Default name of the single report file.
     */
    private static final String DEFAULT_SINGLE_REPORT_FILE = "junit-report.xml";
    /**
     * Default name pattern for multiple report files.
     */
    private static final String DEFAULT_MULTI_REPORT_FILE = "junit-report-" + JUnitReportListener.TOKEN_SUITE + ".xml";

    private static final String LOG_TAG = JUnitReportTestRunner.class.getSimpleName();
    
    private JUnitReportListener mListener;
    private String mReportFile;
    private String mReportDir;
    private boolean mFilterTraces = true;
    private boolean mMultiFile = false;

    // Watch the log in the background for messages indicating a crash in native code.
    private static Thread mLogThread = null;

    private synchronized void startLogThread() {
        if (mLogThread != null) {
            return;
        }

        mLogThread = new Thread() {
            private static final String LOG_KEYWORD_CRASH = "crash";
            private static final String LOG_KEYWORD_FATAL_EXCEPTION = "fatal exception";

            private static final int LOG_MIN_AVAIL_LINES = 10;
            private static final String LOG_MSG_SEPARATOR = "): ";

            @Override
            public void run() {
                BufferedReader buffer = null;
                StringBuilder message = new StringBuilder(8192);
                try {
                    // Only look at messages from specific components.
                    Process process = Runtime.getRuntime().exec("logcat -s ActivityManager AndroidRuntime System.err");
                    InputStreamReader stream = new InputStreamReader(process.getInputStream(), Charset.defaultCharset());
                    buffer = new BufferedReader(stream);

                    String line;
                    while ((line = buffer.readLine()) != null) {
                        String lineLower = line.toLowerCase();
                        if (mListener != null && (lineLower.contains(LOG_KEYWORD_CRASH) || lineLower.contains(LOG_KEYWORD_FATAL_EXCEPTION))) {
                            // Assume we have at least LOG_MIN_AVAIL_LINES lines of stack trace to read.
                            // Decrease this number if the loop does not finish before we are taken down.
                            int lineCount = LOG_MIN_AVAIL_LINES;

                            do {
                                int index = line.indexOf(LOG_MSG_SEPARATOR);
                                if (index >= 0) {
                                    message.append(line.substring(index + LOG_MSG_SEPARATOR.length()));
                                } else {
                                    message.append(line);
                                }
                                message.append("\n");

                                line = buffer.readLine();
                            } while (line != null && --lineCount > 0);

                            mListener.addErrorTag(message.toString());

                            // We do not have the time to set up exception handling in this case
                            // anymore, so call the throwing version of close() here.
                            mListener.closeThrows();
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    try {
                        if (buffer != null) {
                            buffer.close();
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        };

        try {
            // Clear the log in the beginning to not trigger on any past messages.
            Process process = Runtime.getRuntime().exec("logcat -c");
            process.waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        }

        mLogThread.setDaemon(true);
        mLogThread.setName("LogWatcher");
        mLogThread.start();
    }

    @Override
    public void onCreate(Bundle arguments) {
        startLogThread();

        if (arguments != null) {
            Log.i(LOG_TAG, "Created with arguments: " + arguments.keySet());
            mReportFile = arguments.getString(ARG_REPORT_FILE);
            mReportDir = arguments.getString(ARG_REPORT_DIR);
            mFilterTraces = getBooleanArgument(arguments, ARG_FILTER_TRACES, true);
            mMultiFile = getBooleanArgument(arguments, ARG_MULTI_FILE, false);
        } else {
            Log.i(LOG_TAG, "No arguments provided");
        }

        if (mReportFile == null) {
            mReportFile = mMultiFile ? DEFAULT_MULTI_REPORT_FILE : DEFAULT_SINGLE_REPORT_FILE;
            Log.i(LOG_TAG, "Defaulted report file to '" + mReportFile + "'");
        }

        super.onCreate(arguments);
    }

    private boolean getBooleanArgument(Bundle arguments, String name, boolean defaultValue)
    {
        String value = arguments.getString(name);
        if (value == null) {
            return defaultValue;
        } else {
            return Boolean.parseBoolean(value);
        }
    }

    /**
     * Subclass and override this if you want to use a different TestRunner type.
     * 
     * @return the test runner to use
     */
    protected AndroidTestRunner makeAndroidTestRunner() {
        return new AndroidTestRunner();
    }

    @Override
    protected AndroidTestRunner getAndroidTestRunner() {
        AndroidTestRunner runner = makeAndroidTestRunner();
        mListener = new JUnitReportListener(getContext(), getTargetContext(), mReportFile, mReportDir, mFilterTraces, mMultiFile);
        runner.addTestListener(mListener);
        return runner;
    }

    @Override
    public void finish(int resultCode, Bundle results) {
        if (mListener != null) {
            mListener.close();
        }

        super.finish(resultCode, results);
    }
}
