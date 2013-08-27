package org.apache.maven.plugin.pmd;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class for mojos that check if there were any PMD violations.
 *
 * @author <a href="mailto:brett@apache.org">Brett Porter</a>
 * @version $Id$
 */
public abstract class AbstractPmdViolationCheckMojo<D>
    extends AbstractMojo
{
    /**
     * The location of the XML report to check, as generated by the PMD report.
     */
    @Parameter( property = "project.build.directory", required = true )
    private File targetDirectory;

    /**
     * Whether to fail the build if the validation check fails.
     */
    @Parameter( property = "pmd.failOnViolation", defaultValue = "true", required = true )
    protected boolean failOnViolation;

    /**
     * The project language, for determining whether to run the report.
     */
    @Parameter( property = "project.artifact.artifactHandler.language", required = true, readonly = true )
    private String language;

    /**
     * Whether to build an aggregated report at the root, or build individual reports.
     *
     * @since 2.2
     */
    @Parameter( property = "aggregate", defaultValue = "false" )
    protected boolean aggregate;

    /**
     * Print details of check failures to build output.
     */
    @Parameter( property = "pmd.verbose", defaultValue = "false" )
    private boolean verbose;

    /**
     * Print details of errors that cause build failure
     *
     * @since 3.0
     */
    @Parameter( property = "pmd.printFailingErrors", defaultValue = "false" )
    private boolean printFailingErrors;

    /**
     * File that lists classes and rules to be excluded from failures
     * For PMD, this is a properties file
     * For CPD, this is a text file that contains comma-separated lists of classes that are allowed to duplicate
     *
     * @since 3.0
     */
    @Parameter( property = "pmd.excludeFromFailureFile", defaultValue = "" )
    private String excludeFromFailureFile;

    /**
     * The project to analyze.
     */
    @Component
    protected MavenProject project;

    protected void executeCheck( final String filename, final String tagName, final String key,
                                 final int failurePriority )
        throws MojoFailureException, MojoExecutionException
    {
        if ( aggregate && !project.isExecutionRoot() )
        {
            return;
        }

        if ( "java".equals( language ) || aggregate )
        {
            if ( !StringUtils.isEmpty( excludeFromFailureFile ) )
            {
                loadExcludeFromFailuresData( excludeFromFailureFile );
            }
            final File outputFile = new File( targetDirectory, filename );

            if ( outputFile.exists() )
            {
                try
                {
                    final ViolationDetails<D> violations = getViolations( outputFile, failurePriority );

                    final List<D> failures = violations.getFailureDetails();
                    final List<D> warnings = violations.getWarningDetails();

                    if ( verbose )
                    {
                        printErrors( failures, warnings );
                    }

                    final int failureCount = failures.size();
                    final int warningCount = warnings.size();

                    final String message = getMessage( failureCount, warningCount, key, outputFile );

                    getLog().debug( "PMD failureCount: " + failureCount + ", warningCount: " + warningCount );

                    if ( failureCount > 0 && isFailOnViolation() )
                    {
                        throw new MojoFailureException( message );
                    }

                    this.getLog().info( message );
                }
                catch ( final IOException e )
                {
                    throw new MojoExecutionException( "Unable to read PMD results xml: " + outputFile.getAbsolutePath(),
                                                      e );
                }
                catch ( final XmlPullParserException e )
                {
                    throw new MojoExecutionException( "Unable to read PMD results xml: " + outputFile.getAbsolutePath(),
                                                      e );
                }
            }
            else
            {
                throw new MojoFailureException( "Unable to perform check, " + "unable to find " + outputFile );
            }
        }
    }

    protected abstract void loadExcludeFromFailuresData( String excludeFromFailureFile )
        throws MojoExecutionException;

    /**
     * Method for collecting the violations found by the PMD tool
     *
     * @param analysisFile
     * @param failurePriority
     * @return an int that specifies the number of violations found
     * @throws XmlPullParserException
     * @throws IOException
     */
    private ViolationDetails<D> getViolations( final File analysisFile, final int failurePriority )
        throws XmlPullParserException, IOException
    {
        final List<D> failures = new ArrayList<D>();
        final List<D> warnings = new ArrayList<D>();

        final List<D> violations = getErrorDetails( analysisFile );

        for ( final D violation : violations )
        {
            final int priority = getPriority( violation );
            if ( priority <= failurePriority && !isExcludedFromFailure( violation ) )
            {
                failures.add( violation );
                if ( printFailingErrors )
                {
                    printError( violation, "Failure" );
                }
            }
            else
            {
                warnings.add( violation );
            }
        }

        final ViolationDetails<D> details = newViolationDetailsInstance();
        details.setFailureDetails( failures );
        details.setWarningDetails( warnings );
        return details;
    }

    protected abstract int getPriority( D errorDetail );

    protected abstract boolean isExcludedFromFailure( D errorDetail );

    protected abstract ViolationDetails<D> newViolationDetailsInstance();

    /**
     * Prints the warnings and failures
     *
     * @param failures list of failures
     * @param warnings list of warnings
     */
    protected void printErrors( final List<D> failures, final List<D> warnings )
    {
        for ( final D warning : warnings )
        {
            printError( warning, "Warning" );
        }

        for ( final D failure : failures )
        {
            printError( failure, "Failure" );
        }
    }

    /**
     * Gets the output message
     *
     * @param failureCount
     * @param warningCount
     * @param key
     * @param outputFile
     * @return
     */
    private String getMessage( final int failureCount, final int warningCount, final String key, final File outputFile )
    {
        final StringBuilder message = new StringBuilder( 256 );
        if ( failureCount > 0 || warningCount > 0 )
        {
            if ( failureCount > 0 )
            {
                message.append( "You have " ).append( failureCount ).append( " " ).append( key ).append(
                    failureCount > 1 ? "s" : "" );
            }

            if ( warningCount > 0 )
            {
                if ( failureCount > 0 )
                {
                    message.append( " and " );
                }
                else
                {
                    message.append( "You have " );
                }
                message.append( warningCount ).append( " warning" ).append( warningCount > 1 ? "s" : "" );
            }

            message.append( ". For more details see:" ).append( outputFile.getAbsolutePath() );
        }
        return message.toString();
    }

    /**
     * Formats the failure details and prints them as an INFO message
     *
     * @param item
     */
    protected abstract void printError( D item, String severity );

    /**
     * Gets the attributes and text for the violation tag and puts them in a
     * HashMap
     *
     * @param analisysFile
     * @throws XmlPullParserException
     * @throws IOException
     */
    protected abstract List<D> getErrorDetails( File analisysFile )
        throws XmlPullParserException, IOException;

    public boolean isFailOnViolation()
    {
        return failOnViolation;
    }
}