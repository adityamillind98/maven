package org.apache.maven.project;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.maven.Maven;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.ResolutionErrorHandler;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Profile;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.DefaultModelProblem;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingResult;
import org.apache.maven.model.building.ModelProblem;
import org.apache.maven.model.building.UrlModelSource;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.project.artifact.ProjectArtifact;
import org.apache.maven.repository.RepositorySystem;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.util.Os;
import org.codehaus.plexus.util.StringUtils;

/**
 * @version $Id$
 */
@Component(role = ProjectBuilder.class)
public class DefaultProjectBuilder
    implements ProjectBuilder
{

    @Requirement
    private ModelBuilder modelBuilder;

    @Requirement
    private ProjectBuildingHelper projectBuildingHelper;

    @Requirement
    private RepositorySystem repositorySystem;

    @Requirement
    private ResolutionErrorHandler resolutionErrorHandler;

    // ----------------------------------------------------------------------
    // MavenProjectBuilder Implementation
    // ----------------------------------------------------------------------

    public ProjectBuildingResult build( File pomFile, ProjectBuildingRequest configuration )
        throws ProjectBuildingException
    {
        return build( pomFile, true, configuration );
    }

    private ProjectBuildingResult build( File pomFile, boolean localProject, ProjectBuildingRequest configuration )
        throws ProjectBuildingException
    {

        ClassLoader oldContextClassLoader = Thread.currentThread().getContextClassLoader();

        try
        {
            MavenProject project = configuration.getProject();

            List<ModelProblem> modelProblems = null;

            if ( project == null )
            {
                ModelBuildingRequest request = getModelBuildingRequest( configuration, null );
    
                DefaultModelBuildingListener listener = new DefaultModelBuildingListener( projectBuildingHelper, configuration );
                request.setModelBuildingListener( listener );
    
                if ( localProject )
                {
                    request.setPomFile( pomFile );
                }
                else
                {
                    request.setModelSource( new FileModelSource( pomFile ) );
                }
    
                ModelBuildingResult result;
                try
                {
                    result = modelBuilder.build( request );
                }
                catch ( ModelBuildingException e )
                {
                    throw new ProjectBuildingException( e.getModelId(), e.getMessage(), pomFile, e );
                }

                modelProblems = result.getProblems();

                project = toProject( result, configuration, listener );
            }

            ArtifactResolutionResult artifactResult = null;

            if ( configuration.isResolveDependencies() )
            {
                Artifact artifact = new ProjectArtifact( project );

                ArtifactResolutionRequest artifactRequest = new ArtifactResolutionRequest()
                    .setArtifact( artifact )
                    .setResolveRoot( false )
                    .setResolveTransitively( true )
                    .setCache( configuration.getRepositoryCache() )
                    .setLocalRepository( configuration.getLocalRepository() )
                    .setRemoteRepositories( project.getRemoteArtifactRepositories() )
                    .setOffline( configuration.isOffline() )
                    .setManagedVersionMap( project.getManagedVersionMap() );
                // FIXME setTransferListener

                artifactResult = repositorySystem.resolve( artifactRequest );

                project.setArtifacts( artifactResult.getArtifacts() );
            }

            return new DefaultProjectBuildingResult( project, modelProblems, artifactResult );
        }
        finally
        {
            Thread.currentThread().setContextClassLoader( oldContextClassLoader );
        }
    }

    private List<String> getProfileIds( List<Profile> profiles )
    {
        List<String> ids = new ArrayList<String>( profiles.size() );

        for ( Profile profile : profiles )
        {
            ids.add( profile.getId() );
        }

        return ids;
    }

    private ModelBuildingRequest getModelBuildingRequest( ProjectBuildingRequest configuration,
                                                          ReactorModelPool reactorModelPool )
    {
        ModelResolver resolver =
            new RepositoryModelResolver( repositorySystem, resolutionErrorHandler, configuration, reactorModelPool );

        ModelBuildingRequest request = new DefaultModelBuildingRequest();

        request.setValidationLevel( configuration.getValidationLevel() );
        request.setProcessPlugins( configuration.isProcessPlugins() );
        request.setProfiles( configuration.getProfiles() );
        request.setActiveProfileIds( configuration.getActiveProfileIds() );
        request.setInactiveProfileIds( configuration.getInactiveProfileIds() );
        request.setSystemProperties( configuration.getSystemProperties() );
        request.setUserProperties( configuration.getUserProperties() );
        request.setBuildStartTime( configuration.getBuildStartTime() );
        request.setModelResolver( resolver );

        return request;
    }

    public ProjectBuildingResult build( Artifact artifact, ProjectBuildingRequest configuration )
        throws ProjectBuildingException
    {
        if ( !artifact.getType().equals( "pom" ) )
        {
            artifact = repositorySystem.createProjectArtifact( artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion() );
        }

        ArtifactResolutionRequest request = new ArtifactResolutionRequest()
            .setArtifact( artifact )
            .setCache( configuration.getRepositoryCache() )
            .setLocalRepository( configuration.getLocalRepository() )
            .setRemoteRepositories( configuration.getRemoteRepositories() )
            .setOffline( configuration.isOffline() );
        // FIXME setTransferListener
        ArtifactResolutionResult result = repositorySystem.resolve( request );

        try
        {
            resolutionErrorHandler.throwErrors( request, result );
        }
        catch ( ArtifactResolutionException e )
        {
            throw new ProjectBuildingException( artifact.getId(),
                                                "Error resolving project artifact: " + e.getMessage(), e );
        }

        return build( artifact.getFile(), false, configuration );
    }

    /**
     * This is used for pom-less execution like running archetype:generate.
     * 
     * I am taking out the profile handling and the interpolation of the base directory until we
     * spec this out properly.
     */
    public ProjectBuildingResult buildStandaloneSuperProject( ProjectBuildingRequest config )
        throws ProjectBuildingException
    {
        ModelBuildingRequest request = getModelBuildingRequest( config, null );

        DefaultModelBuildingListener listener = new DefaultModelBuildingListener( projectBuildingHelper, config );
        request.setModelBuildingListener( listener );

        request.setModelSource( new UrlModelSource( getClass().getResource( "standalone.xml" ) ) );

        ModelBuildingResult result;
        try
        {
            result = modelBuilder.build( request );
        }
        catch ( ModelBuildingException e )
        {
            throw new ProjectBuildingException( "[standalone]", "Failed to build standalone project", e );
        }

        MavenProject standaloneProject = new MavenProject( result.getEffectiveModel(), repositorySystem, this, config );

        standaloneProject.setActiveProfiles( result.getActiveExternalProfiles() );
        standaloneProject.setInjectedProfileIds( "external", getProfileIds( result.getActiveExternalProfiles() ) );
        standaloneProject.setRemoteArtifactRepositories( listener.getRemoteRepositories() );
        standaloneProject.setPluginArtifactRepositories( listener.getPluginRepositories() );

        standaloneProject.setExecutionRoot( true );

        return new DefaultProjectBuildingResult( standaloneProject, result.getProblems(), null );
    }

    public List<ProjectBuildingResult> build( List<File> pomFiles, boolean recursive, ProjectBuildingRequest config )
        throws ProjectBuildingException
    {
        List<ProjectBuildingResult> results = new ArrayList<ProjectBuildingResult>();

        List<InterimResult> interimResults = new ArrayList<InterimResult>();

        ReactorModelPool reactorModelPool = new ReactorModelPool();

        ReactorModelCache modelCache = new ReactorModelCache();

        boolean errors =
            build( results, interimResults, pomFiles, true, recursive, config, reactorModelPool, modelCache );

        for ( InterimResult interimResult : interimResults )
        {
            Model model = interimResult.result.getEffectiveModel();
            reactorModelPool.put( model.getGroupId(), model.getArtifactId(), model.getVersion(), model.getPomFile() );
        }

        ClassLoader oldContextClassLoader = Thread.currentThread().getContextClassLoader();

        try
        {
            for ( InterimResult interimResult : interimResults )
            {
                try
                {
                    ModelBuildingResult result = modelBuilder.build( interimResult.request, interimResult.result );

                    MavenProject project = toProject( result, config, interimResult.listener );
                    project.setExecutionRoot( interimResult.root );

                    results.add( new DefaultProjectBuildingResult( project, result.getProblems(), null ) );
                }
                catch ( ModelBuildingException e )
                {
                    results.add( new DefaultProjectBuildingResult( e.getModelId(), interimResult.pomFile,
                                                                   e.getProblems() ) );

                    errors = true;
                }
            }
        }
        finally
        {
            Thread.currentThread().setContextClassLoader( oldContextClassLoader );
        }

        if ( errors )
        {
            throw new ProjectBuildingException( results );
        }

        return results;
    }

    private boolean build( List<ProjectBuildingResult> results, List<InterimResult> interimResults,
                           List<File> pomFiles, boolean isRoot, boolean recursive, ProjectBuildingRequest config,
                           ReactorModelPool reactorModelPool, ReactorModelCache modelCache )
    {
        boolean errors = false;

        for ( File pomFile : pomFiles )
        {
            ModelBuildingRequest request = getModelBuildingRequest( config, reactorModelPool );

            request.setPomFile( pomFile );
            request.setTwoPhaseBuilding( true );
            request.setModelCache( modelCache );

            DefaultModelBuildingListener listener = new DefaultModelBuildingListener( projectBuildingHelper, config );
            request.setModelBuildingListener( listener );

            try
            {
                ModelBuildingResult result = modelBuilder.build( request );

                Model model = result.getEffectiveModel();

                interimResults.add( new InterimResult( pomFile, request, result, listener, isRoot ) );

                if ( recursive && !model.getModules().isEmpty() )
                {
                    File basedir = pomFile.getParentFile();

                    List<File> moduleFiles = new ArrayList<File>();

                    for ( String module : model.getModules() )
                    {
                        if ( StringUtils.isEmpty( module ) )
                        {
                            continue;
                        }

                        File moduleFile = new File( basedir, module );

                        if ( moduleFile.isDirectory() )
                        {
                            moduleFile = new File( moduleFile, Maven.POMv4 );
                        }

                        if ( !moduleFile.isFile() )
                        {
                            ModelProblem problem =
                                new DefaultModelProblem( "Child module " + moduleFile + " of " + pomFile
                                    + " does not exist", ModelProblem.Severity.ERROR, model, -1, -1, null );
                            result.getProblems().add( problem );

                            errors = true;

                            continue;
                        }

                        if ( Os.isFamily( Os.FAMILY_WINDOWS ) )
                        {
                            // we don't canonicalize on unix to avoid interfering with symlinks
                            try
                            {
                                moduleFile = moduleFile.getCanonicalFile();
                            }
                            catch ( IOException e )
                            {
                                moduleFile = moduleFile.getAbsoluteFile();
                            }
                        }
                        else
                        {
                            moduleFile = new File( moduleFile.toURI().normalize() );
                        }

                        moduleFiles.add( moduleFile );
                    }

                    if ( build( results, interimResults, moduleFiles, false, recursive, config, reactorModelPool,
                                 modelCache ) )
                    {
                        errors = true;
                    }
                }
            }
            catch ( ModelBuildingException e )
            {
                results.add( new DefaultProjectBuildingResult( e.getModelId(), pomFile, e.getProblems() ) );

                errors = true;
            }
        }

        return errors;
    }

    static class InterimResult
    {

        File pomFile;

        ModelBuildingRequest request;

        ModelBuildingResult result;

        DefaultModelBuildingListener listener;

        boolean root;

        InterimResult( File pomFile, ModelBuildingRequest request, ModelBuildingResult result,
                       DefaultModelBuildingListener listener, boolean root )
        {
            this.pomFile = pomFile;
            this.request = request;
            this.result = result;
            this.listener = listener;
            this.root = root;
        }

    }

    private MavenProject toProject( ModelBuildingResult result, ProjectBuildingRequest configuration,
                                    DefaultModelBuildingListener listener )
    {
        Model model = result.getEffectiveModel();

        MavenProject project = new MavenProject( model, repositorySystem, this, configuration );

        project.setFile( model.getPomFile() );

        File parentPomFile = result.getRawModel( result.getModelIds().get( 1 ) ).getPomFile();
        project.setParentFile( parentPomFile );

        Artifact projectArtifact =
            repositorySystem.createArtifact( project.getGroupId(), project.getArtifactId(), project.getVersion(), null,
                                             project.getPackaging() );
        project.setArtifact( projectArtifact );

        project.setOriginalModel( result.getRawModel() );

        project.setRemoteArtifactRepositories( listener.getRemoteRepositories() );
        project.setPluginArtifactRepositories( listener.getPluginRepositories() );

        project.setClassRealm( listener.getProjectRealm() );

        Build build = project.getBuild();
        project.addScriptSourceRoot( build.getScriptSourceDirectory() );
        project.addCompileSourceRoot( build.getSourceDirectory() );
        project.addTestCompileSourceRoot( build.getTestSourceDirectory() );

        List<Profile> activeProfiles = new ArrayList<Profile>();
        activeProfiles.addAll( result.getActivePomProfiles( result.getModelIds().get( 0 ) ) );
        activeProfiles.addAll( result.getActiveExternalProfiles() );
        project.setActiveProfiles( activeProfiles );

        project.setInjectedProfileIds( "external", getProfileIds( result.getActiveExternalProfiles() ) );
        for ( String modelId : result.getModelIds() )
        {
            project.setInjectedProfileIds( modelId, getProfileIds( result.getActivePomProfiles( modelId ) ) );
        }

        return project;
    }

}