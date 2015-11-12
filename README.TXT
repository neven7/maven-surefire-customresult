maven-surefire-customresult

背景：自定义输出mvn surefire结果， 默认的结果只有Failed 和 Error的失败信息，并且打印的结果格式多样，不方便结果统计和正则匹配失败用例。
默认surefire插件输出结果几种常见的格式：


  AccountUpdatePrivacyCommentTest.setUpBeforeClass:69 1

  AccountUpdatePrivacyCommentTest.testAttentionByComment:136
Expected: is "省略"
     but: was "省略"

com.weibo.cases.maincase.XuelianCommentsHotTimelineWithFilterHotTimelineCommentBVTTest.testFilterCreate(com.weibo.cases.maincase.XuelianCommentsHotTimelineWithFilterHotTimelineCommentBVTTest)
  Run 1: XuelianCommentsHotTimelineWithFilterHotTimelineCommentBVTTest.setUp:79 NullPointer
  Run 2: XuelianCommentsHotTimelineWithFilterHotTimelineCommentBVTTest.tearDown:110
Expected: is "true"
     but: was null     

StatusShoppingTitleStatusTest.testFriendsTimelineRecom:245->createPage:319 This is error,should create page success!
如果断言是用hamcrest，输出结果为多行，因为hamcrest源码使用换行符"\n""打印实际和预期：

https://github.com/hamcrest/JavaHamcrest

path: JavaHamcrest/hamcrest-core/src/main/java/org/hamcrest/MatcherAssert.java

public static <T> void assertThat(String reason, T actual, Matcher<? super T> matcher) {
        if (!matcher.matches(actual)) {
            Description description = new StringDescription();
            description.appendText(reason)
                       .appendText("\nExpected: ")
                       .appendDescriptionOf(matcher)
                       .appendText("\n     but: ");
            matcher.describeMismatch(actual, description);

            throw new AssertionError(description.toString());
        }
    }
实现自定义输出格式：
为了便于mvn test迭代重试，故要统一输出格式，否则需正则匹配各种不样的格式，开销大且易漏掉失败用例，修改了maven surefire源码，统一输出结果并将成功和skipped的用例信息也打印出来，方便统计结果信息：

package org.apache.maven.plugin.surefire.report;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.plugin.surefire.StartupReportConfiguration;
import org.apache.maven.plugin.surefire.runorder.StatisticsReporter;
import org.apache.maven.surefire.report.DefaultDirectConsoleReporter;
import org.apache.maven.surefire.report.ReporterFactory;
import org.apache.maven.surefire.report.RunListener;
import org.apache.maven.surefire.report.RunStatistics;
import org.apache.maven.surefire.report.StackTraceWriter;
import org.apache.maven.surefire.suite.RunResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Provides reporting modules on the plugin side.
 * <p/>
 * Keeps a centralized count of test run results.
 *
 * @author Kristian Rosenvold
 * 
 *         modify by hugang stdout success and skipped
 */
public class DefaultReporterFactory implements ReporterFactory {

    private RunStatistics globalStats = new RunStatistics();

    private final StartupReportConfiguration reportConfiguration;

    private final StatisticsReporter statisticsReporter;

    private final List<TestSetRunListener> listeners = Collections
            .synchronizedList(new ArrayList<TestSetRunListener>());

    // from "<testclass>.<testmethod>" -> statistics about all the runs for
    // flaky tests
    private Map<String, List<TestMethodStats>> flakyTests;

    // from "<testclass>.<testmethod>" -> statistics about all the runs for
    // failed tests
    private Map<String, List<TestMethodStats>> failedTests;

    // from "<testclass>.<testmethod>" -> statistics about all the runs for
    // error tests
    private Map<String, List<TestMethodStats>> errorTests;

    // added by hugang, from "<testclass>.<testmethod>" -> statistics about all
    // the runs for success tests
    private Map<String, List<TestMethodStats>> successTests;

    // added by hugang, from "<testclass>.<testmethod>" -> statistics about all
    // the runs for skipped tests
    private Map<String, List<TestMethodStats>> skippedTests;

    public DefaultReporterFactory(StartupReportConfiguration reportConfiguration) {
        this.reportConfiguration = reportConfiguration;
        this.statisticsReporter = reportConfiguration
                .instantiateStatisticsReporter();
    }

    public RunListener createReporter() {
        return createTestSetRunListener();
    }

    public void mergeFromOtherFactories(
            Collection<DefaultReporterFactory> factories) {
        for (DefaultReporterFactory factory : factories) {
            for (TestSetRunListener listener : factory.listeners) {
                listeners.add(listener);
            }
        }
    }

    public RunListener createTestSetRunListener() {
        TestSetRunListener testSetRunListener = new TestSetRunListener(
                reportConfiguration.instantiateConsoleReporter(),
                reportConfiguration.instantiateFileReporter(),
                reportConfiguration.instantiateStatelessXmlReporter(),
                reportConfiguration.instantiateConsoleOutputFileReporter(),
                statisticsReporter, reportConfiguration.isTrimStackTrace(),
                ConsoleReporter.PLAIN.equals(reportConfiguration
                        .getReportFormat()),
                reportConfiguration.isBriefOrPlainFormat());
        listeners.add(testSetRunListener);
        return testSetRunListener;
    }

    public void addListener(TestSetRunListener listener) {
        listeners.add(listener);
    }

    public RunResult close() {
        mergeTestHistoryResult();
        runCompleted();
        for (TestSetRunListener listener : listeners) {
            listener.close();
        }
        return globalStats.getRunResult();
    }

    private DefaultDirectConsoleReporter createConsoleLogger() {
        return new DefaultDirectConsoleReporter(
                reportConfiguration.getOriginalSystemOut());
    }

    // 测试开始
    public void runStarting() {
        final DefaultDirectConsoleReporter consoleReporter = createConsoleLogger();
        consoleReporter.info("");
        consoleReporter
                .info("-------------------------------------------------------");
        consoleReporter.info(" T E S T S");
        consoleReporter
                .info("-------------------------------------------------------");
    }

    // 测试结束
    private void runCompleted() {
        final DefaultDirectConsoleReporter logger = createConsoleLogger();
        if (reportConfiguration.isPrintSummary()) {
            logger.info("");
            logger.info("Results :");
            logger.info("");
        }
        // 输出不同类型用例信息
        boolean printedFailures = printTestFailures(logger,
                TestResultType.failure);
        printedFailures |= printTestFailures(logger, TestResultType.error);
        printedFailures |= printTestFailures(logger, TestResultType.flake);

        // added by hugang， 添加success and skipped用例详细
        boolean printedSuccessSkipped = printTestSuccessSkipped(logger,
                TestResultType.success);
        printedSuccessSkipped |= printTestSuccessSkipped(logger,
                TestResultType.skipped);

        if (printedFailures) {
            logger.info("");
        }

        if (printedSuccessSkipped) {
            logger.info("");
        }

        // 输出failed 和 error的用例集， 作为下次重跑物料
        // private Map<String, List<TestMethodStats>> failedTests;
        // private Map<String, List<TestMethodStats>> errorTests;
        // logger.info( failedTests.toString() );
        // logger.info( "" );
        // logger.info( errorTests.toString() );

        // globalStats.getSummary() 打印数量
        logger.info(globalStats.getSummary());
        logger.info("");
    }

    public RunStatistics getGlobalRunStatistics() {
        mergeTestHistoryResult();
        return globalStats;
    }

    public static DefaultReporterFactory defaultNoXml() {
        return new DefaultReporterFactory(
                StartupReportConfiguration.defaultNoXml());
    }

    /**
     * Get the result of a test based on all its runs. If it has success and
     * failures/errors, then it is a flake; if it only has errors or failures,
     * then count its result based on its first run
     *
     * @param reportEntryList
     *            the list of test run report type for a given test
     * @param rerunFailingTestsCount
     *            configured rerun count for failing tests
     * @return the type of test result
     */
    // Use default visibility for testing
    static TestResultType getTestResultType(
            List<ReportEntryType> reportEntryList, int rerunFailingTestsCount) {
        if (reportEntryList == null || reportEntryList.isEmpty()) {
            return TestResultType.unknown;
        }

        boolean seenSuccess = false, seenFailure = false, seenError = false;
        for (ReportEntryType resultType : reportEntryList) {
            if (resultType == ReportEntryType.SUCCESS) {
                seenSuccess = true;
            } else if (resultType == ReportEntryType.FAILURE) {
                seenFailure = true;
            } else if (resultType == ReportEntryType.ERROR) {
                seenError = true;
            }
        }

        if (seenFailure || seenError) {
            if (seenSuccess && rerunFailingTestsCount > 0) {
                return TestResultType.flake;
            } else {
                if (seenError) {
                    return TestResultType.error;
                } else {
                    return TestResultType.failure;
                }
            }
        } else if (seenSuccess) {
            return TestResultType.success;
        } else {
            return TestResultType.skipped;
        }
    }

    /**
     * Merge all the TestMethodStats in each TestRunListeners and put results
     * into flakyTests, failedTests and errorTests, indexed by test class and
     * method name. Update globalStatistics based on the result of the merge.
     */
    void mergeTestHistoryResult() {
        globalStats = new RunStatistics();
        flakyTests = new TreeMap<String, List<TestMethodStats>>();
        failedTests = new TreeMap<String, List<TestMethodStats>>();
        errorTests = new TreeMap<String, List<TestMethodStats>>();
        // added by hugang, 存success 和 skpped 用例信息
        successTests = new TreeMap<String, List<TestMethodStats>>();
        skippedTests = new TreeMap<String, List<TestMethodStats>>();
        // key:测试方法， value:结果
        Map<String, List<TestMethodStats>> mergedTestHistoryResult = new HashMap<String, List<TestMethodStats>>();
        // Merge all the stats for tests from listeners
        for (TestSetRunListener listener : listeners) {
            // 遍历每个listener
            List<TestMethodStats> testMethodStats = listener
                    .getTestMethodStats();
            // 遍历每个测试方法结果
            for (TestMethodStats methodStats : testMethodStats) {
                List<TestMethodStats> currentMethodStats = mergedTestHistoryResult
                        .get(methodStats.getTestClassMethodName());
                // 查看mergedTestHistoryResult中是否已存在methodStats.getTestClassMethodName()，第一次添加
                if (currentMethodStats == null) {
                    currentMethodStats = new ArrayList<TestMethodStats>();
                    currentMethodStats.add(methodStats);
                    // 添加到map中，key:方法， value:方法结果
                    mergedTestHistoryResult.put(
                            methodStats.getTestClassMethodName(),
                            currentMethodStats);
                } else {
                    // 方法多个结果
                    currentMethodStats.add(methodStats);
                }
            }
        }

        // Update globalStatistics by iterating through mergedTestHistoryResult
        int completedCount = 0, skipped = 0;
        // 遍历所有的类，判断每一个类中的方法执行结果，放到对应的map中；
        // TestMethodStats每个测试方法信息
        for (Map.Entry<String, List<TestMethodStats>> entry : mergedTestHistoryResult
                .entrySet()) {
            List<TestMethodStats> testMethodStats = entry.getValue();
            String testClassMethodName = entry.getKey();
            completedCount++;

            // 将每个测试方法的执行结果添加到resultTypeList中
            List<ReportEntryType> resultTypeList = new ArrayList<ReportEntryType>();
            for (TestMethodStats methodStats : testMethodStats) {
                resultTypeList.add(methodStats.getResultType());
            }

            TestResultType resultType = getTestResultType(resultTypeList,
                    reportConfiguration.getRerunFailingTestsCount());
            // 根据一个类的不同方法执行结果，放到对应的map中
            switch (resultType) {
            case success:
                // If there are multiple successful runs of the same test, count
                // all of them
                int successCount = 0;
                for (ReportEntryType type : resultTypeList) {
                    if (type == ReportEntryType.SUCCESS) {
                        successCount++;
                    }
                }
                completedCount += successCount - 1;
                // added by hugang, 把success 用例信息，put 到map中
                successTests.put(testClassMethodName, testMethodStats);
                break;
            case skipped:
                // added by hugang, 把skipped 用例信息，put 到map中
                skippedTests.put(testClassMethodName, testMethodStats);
                skipped++;
                break;
            case flake:
                flakyTests.put(testClassMethodName, testMethodStats);
                break;
            case failure:
                failedTests.put(testClassMethodName, testMethodStats);
                break;
            case error:
                errorTests.put(testClassMethodName, testMethodStats);
                break;
            default:
                throw new IllegalStateException("Get unknown test result type");
            }
        }

        globalStats.set(completedCount, errorTests.size(), failedTests.size(),
                skipped, flakyTests.size());
    }

    /**
     * Print failed tests and flaked tests. A test is considered as a failed
     * test if it failed/got an error with all the runs. If a test passes in
     * ever of the reruns, it will be count as a flaked test
     *
     * @param logger
     *            the logger used to log information
     * @param type
     *            the type of results to be printed, could be error, failure or
     *            flake
     * @return {@code true} if printed some lines
     */
    private String statckInfo = "CustomResult Failed StackTrace";

    // Use default visibility for testing
    boolean printTestFailures(DefaultDirectConsoleReporter logger,
            TestResultType type) {
        boolean printed = false;
        final Map<String, List<TestMethodStats>> testStats;
        switch (type) {
        case failure:
            testStats = failedTests;
            break;
        case error:
            testStats = errorTests;
            break;
        case flake:
            testStats = flakyTests;
            break;
        default:
            return printed;
        }

        if (!testStats.isEmpty()) {
            // 被注释，添加到每行用例信息前，便于正则匹配
            // logger.info( type.getLogPrefix() );
            printed = true;
        }

        // 遍历Map:testStats, value: List<TestMethodStats>
        // 元素TestMethodStats：Maintains per-thread test result state for a single
        // test method.
        for (Map.Entry<String, List<TestMethodStats>> entry : testStats
                .entrySet()) {
            printed = true;
            List<TestMethodStats> testMethodStats = entry.getValue();
            if (testMethodStats.size() == 1) {
                // 被注释
                // No rerun, follow the original output format
                // logger.info( "  " + testMethodStats.get( 0
                // ).getStackTraceWriter().smartTrimmedStackTrace() );
                // added by hugang , 每行用例信息前，便于正则匹配
                // 打印失败信息

                // 将错误追踪栈中换行符去掉(hamcrest匹配器错误信息输出多行)，只输出一行，便于正则匹配
                String strFailStrace = testMethodStats.get(0)
                        .getStackTraceWriter().smartTrimmedStackTrace();
                logger.info(statckInfo + "@"
                        + strFailStrace.replaceAll("\n", ""));
                // 只打印失败的类方法，供解析出用例信息
                // getTestClassMethodName()返回格式：
                // com.weibo.cases.hugang.SurefirePluginTest.test1(com.weibo.cases.hugang.SurefirePluginTest)
                // 或com.weibo.cases.maincase.XiaoyuGroupStatusStatusBVTTest.com.weibo.cases.maincase.XiaoyuGroupStatusStatusBVTTest
                logger.info(type.getLogPrefix() + "@"
                        + testMethodStats.get(0).getTestClassMethodName());
            } else {
                // 一个方法有多个结果，比如@BeforeClass,@Before中失败; @BeforeClass失败，输出类似：
                // com.weibo.cases.maincase.XiaoyuGroupStatusStatusBVTTest.
                // com.weibo.cases.maincase.XiaoyuGroupStatusStatusBVTTest
                // logger.info( statckInfo + "@" + entry.getKey() );
                // 将多个结果整合到一行
                // 存1个测试方法中多个运行结果信息
                List<String> mutFailInfo = new ArrayList<String>();
                for (int i = 0; i < testMethodStats.size(); i++) {
                    StackTraceWriter failureStackTrace = testMethodStats.get(i)
                            .getStackTraceWriter();
                    if (failureStackTrace == null) {
                        mutFailInfo.add("  Run " + (i + 1) + ": PASS");
                    } else {
                        mutFailInfo.add("  Run "
                                + (i + 1)
                                + ": "
                                + failureStackTrace.smartTrimmedStackTrace()
                                        .replaceAll("\n", ""));
                    }
                }

                String runInfo = "";
                for (int j = 0; j < mutFailInfo.size(); j++) {
                    runInfo += mutFailInfo.get(j);
                }
                String allFailInfo = statckInfo + "@" + entry.getKey()
                        + runInfo;
                // 打印多个失败集, 统一放到一行，便于匹配
                logger.info(allFailInfo);

                // 打印失败的类方法， 供解析出用例信息
                logger.info(type.getLogPrefix() + "@"
                        + testMethodStats.get(0).getTestClassMethodName());
                logger.info("");
            }
        }
        return printed;
    }

    // 打印成功和skipped用例
    boolean printTestSuccessSkipped(DefaultDirectConsoleReporter logger,
            TestResultType type) {
        boolean printed = false;
        final Map<String, List<TestMethodStats>> testStats;
        switch (type) {
        // added by hugang；支持success and skipped
        case success:
            testStats = successTests;
            break;
        case skipped:
            testStats = skippedTests;
            break;
        default:
            return printed;
        }

        if (!testStats.isEmpty()) {
            // 被注释，添加到每行用例信息前，便于正则匹配
            // logger.info( type.getLogPrefix() );
            printed = true;
        }

        for (Map.Entry<String, List<TestMethodStats>> entry : testStats
                .entrySet()) {
            printed = true;
            List<TestMethodStats> testMethodStats = entry.getValue();
            if (testMethodStats.size() == 1) {
                // 被注释
                // No rerun, follow the original output format
                // logger.info( "  " + testMethodStats.get( 0
                // ).getStackTraceWriter().smartTrimmedStackTrace() );
                // added by hugang , 每行用例信息前，便于正则匹配
                logger.info(type.getLogPrefix() + "@"
                        + testMethodStats.get(0).getTestClassMethodName());
            } else {
                logger.info(entry.getKey());
                for (int i = 0; i < testMethodStats.size(); i++) {
                    logger.info(type.getLogPrefix() + "@"
                            + testMethodStats.get(i).getTestClassMethodName());
                }
                logger.info("");
            }
        }
        return printed;
    }

    // Describe the result of a given test
    static enum TestResultType {

        error("CustomResult Error"), failure("CustomResult Fail"), flake(
                "CustomResult Flaked"), success("CustomResult Success"), skipped(
                "CustomResult Skipped"), unknown("CustomResult Unknown");

        private final String logPrefix;

        private TestResultType(String logPrefix) {
            this.logPrefix = logPrefix;
        }

        public String getLogPrefix() {
            return logPrefix;
        }
    }
}
当你执行mvn test -D=TestClass时，终端结果输出格式统一输出如下（并且每条用例信息都为1行，方便正则匹配）：
CustomResult Failed StackTrace@错误栈信息

CustomResult Fail@失败信息

CustomResult Error@失败信息

CustomResult Skipped@skipped信息

CustomResult Success@成功信息
使用方法：
https://github.com/neven7/maven-surefire-customresult 选择分支 Branch: extensions-2.19

下载源码

版本已经修改为2.19.1

1.本地使用，进入项目根目录，执行mvn clean -Dcheckstyle.skip=true -Dmaven.test.skip=true -DuniqueVersion=false -Denforcer.skip=true install 本地安装， 在测试项目pom.xml中引用如下：

<plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>2.19.1</version>
                <configuration>
                    <forkMode>pertest</forkMode>
                    <argLine>-Xms1024m -Xmx1024m</argLine>
                    <printSummary>true</printSummary>
                    <testFailureIgnore>true</testFailureIgnore>
                </configuration>
                <dependencies>
                    <dependency>
                        <groupId>org.apache.maven.surefire</groupId>
                        <artifactId>surefire-junit47</artifactId>
                        <version>2.19</version>
                    </dependency>
                </dependencies>
            </plugin>
        </plugins>
2.公司内部使用，进入项目根目录，执行mvn clean -Dcheckstyle.skip=true -Dmaven.test.skip=true -DuniqueVersion=false -Denforcer.skip=true deploy 需要在项目pom.xml配置自己公司的私有仓库：

<distributionManagement>
        <repository>
            <id>weiboqa</id>
            <name>weiboqacontent</name>
            <url>http://ip:port/nexus/content/repositories/weiboqa</url>
        </repository>
 </distributionManagement>
在settings.xml配置私有仓库的账号和密码才能发布到私有仓库，发布成功后，测试项目pom.xml引用如上。
