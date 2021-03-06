/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.compatibility.common.tradefed.presubmit;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.compatibility.common.tradefed.targetprep.FilePusher;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.ConfigurationFactory;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.targetprep.ITargetPreparer;
import com.android.tradefed.targetprep.PushFilePreparer;
import com.android.tradefed.targetprep.TestAppInstallSetup;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.InstrumentationTest;
import com.android.tradefed.util.AaptParser;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Class to validate tests Apks in testcases/
 */
@RunWith(JUnit4.class)
public class ApkPackageNameCheck {

    private static final Set<String> EXCEPTION_LIST = new HashSet<>();
    static {
        // TODO: Remove exception when their package have been fixed.
        EXCEPTION_LIST.add("android.app.cts");
        EXCEPTION_LIST.add("android.systemui.cts");
    }

    /**
     * We ensure that no apk with same package names may be installed. Otherwise it may results in
     * conflicts.
     */
    @Test
    public void testApkPackageNames() throws Exception {
        String ctsRoot = System.getProperty("CTS_ROOT");
        File testcases = new File(ctsRoot, "/android-cts/testcases/");
        if (!testcases.exists()) {
            fail(String.format("%s does not exists", testcases));
            return;
        }
        File[] listConfig = testcases.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                if (name.endsWith(".config")) {
                    return true;
                }
                return false;
            }
        });
        assertTrue(listConfig.length > 0);
        // We check all apk installed by all modules
        Map<String, String> packageNames = new HashMap<>();

        for (File config : listConfig) {
            IConfiguration c = ConfigurationFactory.getInstance()
                    .createConfigurationFromArgs(new String[] {config.getAbsolutePath()});
            // For each config, we check all the apk it's going to install
            List<File> apkNames = new ArrayList<>();
            List<String> packageListNames = new ArrayList<>();
            for (ITargetPreparer prep : c.getTargetPreparers()) {
                if (prep instanceof TestAppInstallSetup) {
                    apkNames.addAll(((TestAppInstallSetup) prep).getTestsFileName());
                }
                // Ensure the files requested to be pushed exist.
                if (prep instanceof FilePusher && ((FilePusher) prep).shouldAppendBitness()) {
                    for (File f : ((PushFilePreparer) prep).getPushSpecs(null).values()) {
                        String path = f.getPath();
                        if (!new File(testcases, path + "32").exists()
                                || !new File(testcases, path + "64").exists()) {
                            // TODO: Enforce should abort on failure is True in CTS
                            if (((FilePusher) prep).shouldAbortOnFailure()) {
                                fail(
                                        String.format(
                                                "File %s[32/64] wasn't found in testcases/ while "
                                                        + "it's expected to be pushed as part of "
                                                        + "%s",
                                                path, config.getName()));
                            }
                        }
                    }
                } else if (prep instanceof PushFilePreparer) {
                    for (File f : ((PushFilePreparer) prep).getPushSpecs(null).values()) {
                        String path = f.getPath();
                        if (!new File(testcases, path).exists()) {
                            // TODO: Enforce should abort on failure is True in CTS
                            if (((PushFilePreparer) prep).shouldAbortOnFailure()) {
                                fail(
                                        String.format(
                                                "File %s wasn't found in testcases/ while it's "
                                                        + "expected to be pushed as part of %s",
                                                path, config.getName()));
                            }
                        }
                    }
                }
            }

            for (File apk : apkNames) {
                String apkName = apk.getName();
                File apkFile = new File(testcases, apkName);
                if (!apkFile.exists()) {
                    fail(String.format("Module %s is trying to install %s which does not "
                            + "exists in testcases/", config.getName(), apkFile));
                }
                AaptParser res = AaptParser.parse(apkFile);
                assertNotNull(res);
                String packageName = res.getPackageName();
                String put = packageNames.put(packageName, apkName);
                packageListNames.add(packageName);
                // The package already exists and it's a different apk
                if (put != null && !apkName.equals(put) && !EXCEPTION_LIST.contains(packageName)) {
                    fail(String.format("Module %s: Package name '%s' from apk '%s' was already "
                            + "added by previous apk '%s'.",
                            config.getName(), packageName, apkName, put));
                }
            }

            // Catch a test trying to run something it doesn't install.
            List<IRemoteTest> tests = c.getTests();
            for (IRemoteTest test : tests) {
                if (test instanceof InstrumentationTest) {
                    InstrumentationTest instrumentationTest = (InstrumentationTest) test;
                    if (instrumentationTest.getPackageName() != null) {
                        if (!packageListNames.contains(instrumentationTest.getPackageName())) {
                            throw new ConfigurationException(
                                    String.format("Module %s requests to run '%s' but it's not "
                                        + "part of any apks.",
                                        config.getName(), instrumentationTest.getPackageName()));
                        }
                    }
                }
            }
        }
    }
}
