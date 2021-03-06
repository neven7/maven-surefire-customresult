package org.apache.maven.surefire.testng;

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

import org.apache.maven.surefire.booter.Command;
import org.apache.maven.surefire.booter.MasterProcessListener;
import org.apache.maven.surefire.booter.MasterProcessReader;
import org.apache.maven.surefire.cli.CommandLineOption;
import org.apache.maven.surefire.providerapi.AbstractProvider;
import org.apache.maven.surefire.providerapi.ProviderParameters;
import org.apache.maven.surefire.report.ReporterConfiguration;
import org.apache.maven.surefire.report.ReporterFactory;
import org.apache.maven.surefire.suite.RunResult;
import org.apache.maven.surefire.testng.utils.FailFastEventsSingleton;
import org.apache.maven.surefire.testset.TestListResolver;
import org.apache.maven.surefire.testset.TestRequest;
import org.apache.maven.surefire.testset.TestSetFailedException;
import org.apache.maven.surefire.util.RunOrderCalculator;
import org.apache.maven.surefire.util.ScanResult;
import org.apache.maven.surefire.util.TestsToRun;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * @author Kristian Rosenvold
 * @noinspection UnusedDeclaration
 */
public class TestNGProvider
    extends AbstractProvider
{
    private final Map<String, String> providerProperties;

    private final ReporterConfiguration reporterConfiguration;

    private final ClassLoader testClassLoader;

    private final ScanResult scanResult;

    private final TestRequest testRequest;

    private final ProviderParameters providerParameters;

    private final RunOrderCalculator runOrderCalculator;

    private final List<CommandLineOption> mainCliOptions;

    private final MasterProcessReader commandsReader;

    private TestsToRun testsToRun;

    public TestNGProvider( ProviderParameters booterParameters )
    {
        // don't start a thread in MasterProcessReader while we are in in-plugin process
        commandsReader = booterParameters.isInsideFork()
            ? MasterProcessReader.getReader().setShutdown( booterParameters.getShutdown() )
            : null;
        providerParameters = booterParameters;
        testClassLoader = booterParameters.getTestClassLoader();
        runOrderCalculator = booterParameters.getRunOrderCalculator();
        providerProperties = booterParameters.getProviderProperties();
        testRequest = booterParameters.getTestRequest();
        reporterConfiguration = booterParameters.getReporterConfiguration();
        scanResult = booterParameters.getScanResult();
        mainCliOptions = booterParameters.getMainCliOptions();
    }

    public RunResult invoke( Object forkTestSet )
        throws TestSetFailedException
    {
        RunResult runResult;
        try
        {
            if ( isFailFast() && commandsReader != null )
            {
                registerPleaseStopListener();
            }

            if ( commandsReader != null )
            {
                commandsReader.awaitStarted();
            }

            final ReporterFactory reporterFactory = providerParameters.getReporterFactory();

            try
            {

                if ( isTestNGXmlTestSuite( testRequest ) )
                {
                    TestNGXmlTestSuite testNGXmlTestSuite = newXmlSuite();
                    testNGXmlTestSuite.locateTestSets( testClassLoader );
                    if ( forkTestSet != null && testRequest == null )
                    {
                        testNGXmlTestSuite.execute( (String) forkTestSet, reporterFactory );
                    }
                    else
                    {
                        testNGXmlTestSuite.execute( reporterFactory );
                    }
                }
                else
                {
                    if ( testsToRun == null )
                    {
                        if ( forkTestSet instanceof TestsToRun )
                        {
                            testsToRun = (TestsToRun) forkTestSet;
                        }
                        else if ( forkTestSet instanceof Class )
                        {
                            testsToRun = TestsToRun.fromClass( (Class<?>) forkTestSet );
                        }
                        else
                        {
                            testsToRun = scanClassPath();
                        }
                    }

                    if ( commandsReader != null )
                    {
                        commandsReader.addShutdownListener( new MasterProcessListener()
                        {
                            public void update( Command command )
                            {
                                testsToRun.markTestSetFinished();
                            }
                        } );
                    }
                    TestNGDirectoryTestSuite suite = newDirectorySuite();
                    suite.execute( testsToRun, reporterFactory );
                }

            }
            finally
            {
                runResult = reporterFactory.close();
            }
        }
        finally
        {
            closeCommandsReader();
        }
        return runResult;
    }

    boolean isTestNGXmlTestSuite( TestRequest testSuiteDefinition )
    {
        Collection<File> suiteXmlFiles = testSuiteDefinition.getSuiteXmlFiles();
        return !suiteXmlFiles.isEmpty() && !hasSpecificTests();
    }

    private boolean isFailFast()
    {
        return providerParameters.getSkipAfterFailureCount() > 0;
    }

    private int getSkipAfterFailureCount()
    {
        return isFailFast() ? providerParameters.getSkipAfterFailureCount() : 0;
    }

    private void closeCommandsReader()
    {
        if ( commandsReader != null )
        {
            commandsReader.stop();
        }
    }

    private MasterProcessListener registerPleaseStopListener()
    {
        MasterProcessListener listener = new MasterProcessListener()
        {
            public void update( Command command )
            {
                FailFastEventsSingleton.getInstance().setSkipOnNextTest();
            }
        };
        commandsReader.addSkipNextListener( listener );
        return listener;
    }

    private TestNGDirectoryTestSuite newDirectorySuite()
    {
        return new TestNGDirectoryTestSuite( testRequest.getTestSourceDirectory().toString(), providerProperties,
                                             reporterConfiguration.getReportsDirectory(), createMethodFilter(),
                                             runOrderCalculator, scanResult, mainCliOptions,
                                             getSkipAfterFailureCount() );
    }

    private TestNGXmlTestSuite newXmlSuite()
    {
        return new TestNGXmlTestSuite( testRequest.getSuiteXmlFiles(),
                                       testRequest.getTestSourceDirectory().toString(),
                                       providerProperties,
                                       reporterConfiguration.getReportsDirectory(), getSkipAfterFailureCount() );
    }

    @SuppressWarnings( "unchecked" )
    public Iterable<Class<?>> getSuites()
    {
        if ( isTestNGXmlTestSuite( testRequest ) )
        {
            try
            {
                return newXmlSuite().locateTestSets( testClassLoader ).keySet();
            }
            catch ( TestSetFailedException e )
            {
                throw new RuntimeException( e );
            }
        }
        else
        {
            testsToRun = scanClassPath();
            return testsToRun;
        }
    }

    private TestsToRun scanClassPath()
    {
        final TestsToRun scanned = scanResult.applyFilter( null, testClassLoader );
        return runOrderCalculator.orderTestClasses( scanned );
    }

    private boolean hasSpecificTests()
    {
        TestListResolver tests = testRequest.getTestListResolver();
        return tests != null && !tests.isEmpty();
    }

    private TestListResolver createMethodFilter()
    {
        TestListResolver tests = testRequest.getTestListResolver();
        return tests == null ? null : tests.createMethodFilters();
    }
}
