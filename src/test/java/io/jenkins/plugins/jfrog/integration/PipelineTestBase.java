package io.jenkins.plugins.jfrog.integration;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.FilePath;
import hudson.model.Label;
import hudson.model.ModelObject;
import hudson.model.Saveable;
import hudson.model.Slave;
import hudson.tasks.Maven;
import hudson.tools.InstallSourceProperty;
import hudson.tools.ToolProperty;
import hudson.tools.ToolPropertyDescriptor;
import hudson.util.DescribableList;
import hudson.util.Secret;
import io.jenkins.plugins.jfrog.ArtifactoryInstaller;
import io.jenkins.plugins.jfrog.BinaryInstaller;
import io.jenkins.plugins.jfrog.JfrogInstallation;
import io.jenkins.plugins.jfrog.ReleasesInstaller;
import io.jenkins.plugins.jfrog.configuration.Credentials;
import io.jenkins.plugins.jfrog.configuration.CredentialsConfig;
import io.jenkins.plugins.jfrog.configuration.JFrogPlatformBuilder;
import io.jenkins.plugins.jfrog.configuration.JFrogPlatformInstance;
import io.jenkins.plugins.jfrog.jenkins.EnableJenkins;
import jenkins.model.Jenkins;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.text.StringSubstitutor;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jfrog.artifactory.client.Artifactory;
import org.jfrog.artifactory.client.ArtifactoryClientBuilder;
import org.jfrog.artifactory.client.ArtifactoryRequest;
import org.jfrog.artifactory.client.ArtifactoryResponse;
import org.jfrog.artifactory.client.impl.ArtifactoryRequestImpl;
import org.junit.Assert;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.ToolInstallations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@EnableJenkins
public class PipelineTestBase {
    private static long currentTime;
    private static Artifactory artifactoryClient;
    private static JenkinsRule jenkins;
    private static Slave slave;
    private static StringSubstitutor pipelineSubstitution;
    private static final String SLAVE_LABEL = "TestSlave";
    private static final String PLATFORM_URL = System.getenv("JENKINS_PLATFORM_URL");
    private static final String ARTIFACTORY_URL = StringUtils.removeEnd(PLATFORM_URL, "/") + "/artifactory";
    private static final String ARTIFACTORY_USERNAME = System.getenv("JENKINS_PLATFORM_USERNAME");
    private static final String ARTIFACTORY_PASSWORD = System.getenv("JENKINS_ARTIFACTORY_PASSWORD");
    private static final String ACCESS_TOKEN = System.getenv("JENKINS_PLATFORM_ADMIN_TOKEN");
    private static final Path INTEGRATION_BASE_PATH = Paths.get(".").toAbsolutePath().normalize()
            .resolve(Paths.get("src", "test", "resources", "integration"));
    static final String JFROG_CLI_TOOL_NAME_1 = "jfrog-cli";
    static final String JFROG_CLI_TOOL_NAME_2 = "jfrog-cli-2";
    static final String TEST_CONFIGURED_SERVER_ID = "serverId";

    public void initPipelineTest(JenkinsRule jenkins) throws Exception {
        setupPipelineTest(jenkins);
        // Download the latest CLI version.
        configureJfrogCliFromReleases(StringUtils.EMPTY, true);
    }

    // Set up test' environment
    public void setupPipelineTest(JenkinsRule jenkins) throws IOException {
        PipelineTestBase.jenkins = jenkins;
        setUp();
    }

    /**
     * Creates Artifactory Java clients.
     */
    private static void createClients() {
        artifactoryClient = ArtifactoryClientBuilder.create()
                .setUrl(ARTIFACTORY_URL)
                .setUsername(ARTIFACTORY_USERNAME)
                .setPassword(ARTIFACTORY_PASSWORD)
                .setAccessToken(ACCESS_TOKEN)
                .build();
    }

    private static void createSlave() {
        try {
            slave = jenkins.createOnlineSlave(Label.get(SLAVE_LABEL));
        } catch (Exception e) {
            fail(ExceptionUtils.getRootCauseMessage(e));
        }
    }

    public static void setUp() throws IOException {
        currentTime = System.currentTimeMillis();
        verifyEnvironment();
        createSlave();
        createClients();
        setGlobalConfiguration();
        createPipelineSubstitution();
        // Create repositories
        Arrays.stream(TestRepository.values()).forEach(PipelineTestBase::createRepo);
    }

    /**
     * Run pipeline script.
     *
     * @param name - Pipeline name from 'jenkins-jfrog-plugin/src/test/resources/integration/pipelines'
     * @return the Jenkins job
     */
    WorkflowRun runPipeline(JenkinsRule jenkins, String name) throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class);
        FilePath slaveWs = slave.getWorkspaceFor(project);
        if (slaveWs == null) {
            throw new Exception("Slave workspace not found");
        }
        slaveWs.mkdirs();
        project.setDefinition(new CpsFlowDefinition(readPipeline(name), false));
        WorkflowRun job = jenkins.buildAndAssertSuccess(project);
        return job;
    }

    /**
     * Verify ARTIFACTORY_URL, ARTIFACTORY_USERNAME and ACCESS_TOKEN/ARTIFACTORY_PASSWORD were provided.
     */
    private static void verifyEnvironment() {
        if (StringUtils.isBlank(PLATFORM_URL)) {
            throw new IllegalArgumentException("JENKINS_PLATFORM_URL is not set");
        }
        if (StringUtils.isBlank(ARTIFACTORY_USERNAME)) {
            throw new IllegalArgumentException("JENKINS_PLATFORM_USERNAME is not set");
        }
        if (StringUtils.isBlank(ACCESS_TOKEN) && StringUtils.isBlank(ARTIFACTORY_PASSWORD)) {
            throw new IllegalArgumentException("JENKINS_PLATFORM_ADMIN_TOKEN or JENKINS_PLATFORM_PASSWORD are not set");
        }
    }

    /**
     * Configure a new JFrog server in the Global configuration.
     */
    private static void setGlobalConfiguration() throws IOException {
        JFrogPlatformBuilder.DescriptorImpl jfrogBuilder = (JFrogPlatformBuilder.DescriptorImpl) jenkins.getInstance().getDescriptor(JFrogPlatformBuilder.class);
        Assert.assertNotNull(jfrogBuilder);
        CredentialsConfig emptyCred = new CredentialsConfig(StringUtils.EMPTY, Credentials.EMPTY_CREDENTIALS);
        CredentialsConfig platformCred = new CredentialsConfig(Secret.fromString(ARTIFACTORY_USERNAME), Secret.fromString(ARTIFACTORY_PASSWORD), Secret.fromString(ACCESS_TOKEN), "credentials");
        List<JFrogPlatformInstance> artifactoryServers = new ArrayList<JFrogPlatformInstance>() {{
            // Dummy server to test multiple configured servers.
            // The dummy server should be configured first to ensure the right server is being used (and not the first one).
            add(new JFrogPlatformInstance("dummyServerId", "", emptyCred, "", "", ""));
            add(new JFrogPlatformInstance(TEST_CONFIGURED_SERVER_ID, PLATFORM_URL, platformCred, ARTIFACTORY_URL, "", ""));
        }};
        jfrogBuilder.setJfrogInstances(artifactoryServers);
        Jenkins.get().getDescriptorByType(JFrogPlatformBuilder.DescriptorImpl.class).setJfrogInstances(artifactoryServers);
        CredentialsStore store = lookupStore(jenkins);
        addCredentials(store);
    }

    private static void addCredentials(CredentialsStore store) throws IOException {
        // For purposes of this test we do not care about domains.
        store.addCredentials(Domain.global(), new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, "credentials", null, ARTIFACTORY_USERNAME, ARTIFACTORY_PASSWORD));
    }

    private static CredentialsStore lookupStore(ModelObject object) {
        Iterator<CredentialsStore> stores = CredentialsProvider.lookupStores(object).iterator();
        assertTrue(stores.hasNext());
        return stores.next();
    }

    /**
     * Create a temporary repository for the tests.
     *
     * @param repository - The repository base name.
     */
    private static void createRepo(TestRepository repository) {
        ArtifactoryResponse response = null;
        try {
            String repositorySettings = readConfigurationWithSubstitution(repository.getRepoName());
            response = artifactoryClient.restCall(new ArtifactoryRequestImpl()
                    .method(ArtifactoryRequest.Method.PUT)
                    .requestType(ArtifactoryRequest.ContentType.JSON)
                    .apiUrl("api/repositories/" + getRepoKey(repository))
                    .requestBody(repositorySettings));
        } catch (Exception e) {
            fail(ExceptionUtils.getRootCauseMessage(e));
        }
        if (!response.isSuccessResponse()) {
            fail(String.format("Failed creating repository %s: %s", getRepoKey(repository), response.getStatusLine()));
        }
    }

    /**
     * Get the repository key of the temporary test repository.
     *
     * @param repository - The repository base name
     * @return repository key of the temporary test repository
     */
    static String getRepoKey(TestRepository repository) {
        return String.format("%s-%d", repository.getRepoName(), currentTime);
    }

    /**
     * Read repository configuration and replace placeholders with their corresponding values.
     *
     * @param repoOrProject - Name of configuration in resources.
     * @return The configuration after substitution.
     */
    private static String readConfigurationWithSubstitution(String repoOrProject) {
        try {
            return FileUtils.readFileToString(INTEGRATION_BASE_PATH
                    .resolve("settings")
                    .resolve(repoOrProject + ".json").toFile(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            fail(ExceptionUtils.getRootCauseMessage(e));
        }
        return null;
    }

    /**
     * Creates string substitution for the pipelines. The tests use it to replace strings in the pipelines after
     * loading them.
     */
    private static void createPipelineSubstitution() {
        pipelineSubstitution = new StringSubstitutor(new HashMap<String, String>() {{
            put("DUMMY_FILE_PATH", fixWindowsPath(String.valueOf(INTEGRATION_BASE_PATH.resolve("files").resolve("dummyfile"))));
            put("LOCAL_REPO", getRepoKey(TestRepository.LOCAL_REPO));
            put("REMOTE_REPO", getRepoKey(TestRepository.CLI_REMOTE_REPO));
            put("JFROG_CLI_TOOL_NAME_1", JFROG_CLI_TOOL_NAME_1);
            put("JFROG_CLI_TOOL_NAME_2", JFROG_CLI_TOOL_NAME_2);
            put("TEST_CONFIGURED_SERVER_ID", TEST_CONFIGURED_SERVER_ID);
        }});
    }

    /**
     * Escape backslashes in filesystem path.
     *
     * @param path - Filesystem path to fix
     * @return path compatible with Windows
     */
    static String fixWindowsPath(String path) {
        return StringUtils.replace(path, "\\", "\\\\");
    }

    /**
     * Read pipeline from 'jenkins-jfrog-plugin/src/test/resources/integration/pipelines'.
     *
     * @param name - The pipeline name.
     * @return pipeline as a string.
     */
    private String readPipeline(String name) throws IOException {
        String pipeline = FileUtils.readFileToString(INTEGRATION_BASE_PATH
                .resolve("pipelines")
                .resolve(name + ".pipeline").toFile(), StandardCharsets.UTF_8);
        return pipelineSubstitution.replace(pipeline);
    }

    public static void configureJfrogCliFromReleases(String cliVersion, Boolean override) throws Exception {
        configureJfrogCliTool(JFROG_CLI_TOOL_NAME_1, new ReleasesInstaller(cliVersion), override);
    }

    public static void configureJfrogCliFromArtifactory(String toolName, String serverId, String repo, Boolean override) throws Exception {
        configureJfrogCliTool(toolName, new ArtifactoryInstaller(serverId, repo), override);
    }

    /**
     * Add a new JFrog CLI tool.
     *
     * @param toolName  the tool name.
     * @param installer the tool installer (Releases or Artifactory).
     * @param override  The tool will override pre-configured ones and be set if true, otherwise it will be added to the installation array.
     * @return the new tool's JfrogInstallation.
     * @throws IOException failed to configure the new tool.
     */
    public static void configureJfrogCliTool(String toolName, BinaryInstaller installer, Boolean override) throws Exception {
        Saveable NOOP = () -> {
        };
        DescribableList<ToolProperty<?>, ToolPropertyDescriptor> toolProperties = new DescribableList<>(NOOP);
        List<BinaryInstaller> installers = new ArrayList<>();
        installers.add(installer);
        toolProperties.add(new InstallSourceProperty(installers));
        JfrogInstallation jf = new JfrogInstallation(toolName, "", toolProperties);
        ArrayList<JfrogInstallation> installationsArrayList = new ArrayList<>();
        installationsArrayList.add(jf);
        // Get all pre-configured installations and add them to the new one.
        if (!override) {
            JfrogInstallation[] installations = Jenkins.get().getDescriptorByType(JfrogInstallation.DescriptorImpl.class).getInstallations();
            installationsArrayList.addAll(Arrays.asList(installations));
        }
        JfrogInstallation[] installations = installationsArrayList.toArray(new JfrogInstallation[0]);
        Jenkins.get().getDescriptorByType(JfrogInstallation.DescriptorImpl.class).setInstallations(installations);
        //ToolInstallations.configureMaven35();

        return;
    }

    public static Maven.MavenInstallation configureMaven36() throws Exception {
        Maven.MavenInstallation mvn = ToolInstallations.configureMaven35();

        Maven.MavenInstallation m3 = new Maven.MavenInstallation("apache-maven-3.6.3", mvn.getHome(), JenkinsRule.NO_PROPERTIES);
        Jenkins.getInstance().getDescriptorByType(Maven.DescriptorImpl.class).setInstallations(m3);
        return m3;
    }


}