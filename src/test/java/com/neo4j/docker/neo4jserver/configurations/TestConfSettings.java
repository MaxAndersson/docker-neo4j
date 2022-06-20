package com.neo4j.docker.neo4jserver.configurations;

import com.neo4j.docker.utils.DatabaseIO;
import com.neo4j.docker.utils.HostFileSystemOperations;
import com.neo4j.docker.utils.Neo4jVersion;
import com.neo4j.docker.utils.SetContainerUser;
import com.neo4j.docker.utils.TestSettings;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.output.WaitingConsumer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;


public class TestConfSettings
{
    private static Logger log = LoggerFactory.getLogger(TestConfSettings.class);
    private static Path confFolder;
    private static Map<Setting,Configuration> confNames;

    @BeforeAll
    static void getVersionSpecificConfigurationSettings()
    {
        confFolder = Configuration.getConfigurationResourcesFolder( TestSettings.NEO4J_VERSION );
        confNames = Configuration.getConfigurationNameMap( TestSettings.NEO4J_VERSION );
    }

    private GenericContainer createContainer()
    {
        return new GenericContainer(TestSettings.IMAGE_ID)
                .withEnv("NEO4J_AUTH", "none")
                .withEnv("NEO4J_ACCEPT_LICENSE_AGREEMENT", "yes")
                .withExposedPorts(7474, 7687)
                .withLogConsumer(new Slf4jLogConsumer(log));
    }

    private GenericContainer makeContainerDumpConfig(GenericContainer container)
    {
        container.setWaitStrategy( Wait.forLogMessage( ".*Config Dumped.*", 1 )
                                       .withStartupTimeout( Duration.ofSeconds( 30 ) ) );
        container.setCommand("dump-config");
        // what is StartupCheckStrategy you wonder. Well, let me tell you a story.
        // There was a time all these tests were failing because the config file was being dumped
        // and the container closed so quickly. So quickly that it exposed a race condition between the container
        // and the TestContainers library. The container could start and finish before the container library
        // got around to checking if the container had started.
        // The default "Has the container started" check strategy is to see if the container is running.
        // But our container wasn't running because it was so quick it had already finished! The check failed and we had flaky tests :(
        // This strategy here will check to see if the container is running OR if it exited with status code 0.
        // It seems to do what we need... FOR NOW??
        container.setStartupCheckStrategy( new OneShotStartupCheckStrategy() );
        SetContainerUser.nonRootUser( container );
        return container;
    }

    private GenericContainer makeContainerWaitForNeo4jReady(GenericContainer container)
    {
        container.setWaitStrategy( Wait.forHttp( "/" )
                                       .forPort( 7474 )
                                       .forStatusCode( 200 )
                                       .withStartupTimeout( Duration.ofSeconds( 30 ) ) );
        return container;
    }

    private Map<String, String> parseConfFile(File conf) throws FileNotFoundException
    {
        Map<String, String> configurations = new HashMap<>();
        Scanner scanner = new Scanner(conf);
        while ( scanner.hasNextLine() )
        {
            String[] params = scanner.nextLine().split( "=", 2 );
            if(params.length < 2)
            {
                continue;
            }
            log.debug( params[0] + "\t:\t" + params[1] );
            configurations.put( params[0], params[1] );
        }
        return configurations;
    }

    private void assertConfigurationPresentInDebugLog( Path debugLog, Configuration setting, String value, boolean shouldBeFound ) throws IOException
    {
        assertConfigurationPresentInDebugLog( debugLog, setting, new String[]{value}, shouldBeFound );
    }

    private void assertConfigurationPresentInDebugLog( Path debugLog, Configuration setting, String[] eitherOfValues, boolean shouldBeFound ) throws IOException
    {
        // searches the debug log for the given string, returns true if present
        Stream<String> lines = Files.lines(debugLog);
        String actualSetting = lines.filter(s -> s.contains( setting.name )).findFirst().orElse( "" );
        lines.close();
        if(shouldBeFound)
        {
            Assertions.assertTrue( !actualSetting.isEmpty(), setting.name+" was never set" );
            Assertions.assertTrue( Arrays.stream( eitherOfValues ).anyMatch( actualSetting::contains ),
                                   setting.name +" is set to the wrong value. Expected either of: "+
                                   Arrays.toString( eitherOfValues ) +" Actual: " + actualSetting );
        }
        else
        {
            Assertions.assertTrue( actualSetting.isEmpty(),setting.name+" was set when it should not have been. " +
                                                           "Actual value: "+actualSetting );
        }
    }

    @Test
    void testIgnoreNumericVars()
    {
        try(GenericContainer container = createContainer())
        {
            container.withEnv( "NEO4J_1a", "1" );
            container.start();
            Assertions.assertTrue( container.isRunning() );

            WaitingConsumer waitingConsumer = new WaitingConsumer();
            container.followOutput( waitingConsumer );

			Assertions.assertDoesNotThrow( () -> waitingConsumer.waitUntil( frame -> frame.getUtf8String()
					  .contains( "WARNING: 1a not written to conf file because settings that start with a number are not permitted" ),
				15, TimeUnit.SECONDS ),
			   "Neo4j did not warn about invalid numeric config variable `Neo4j_1a`" );
		}
	}

    @Test
    void testEnvVarsOverrideDefaultConfigurations() throws Exception
    {
        Assumptions.assumeTrue(TestSettings.NEO4J_VERSION.isAtLeastVersion(new Neo4jVersion(3, 0, 0)),
                               "No neo4j-admin in 2.3: skipping neo4j-admin-conf-override test");

        File conf;
        try(GenericContainer container = createContainer()
                .withEnv(confNames.get( Setting.MEMORY_PAGECACHE_SIZE ).envName, "1000m")
                .withEnv(confNames.get( Setting.MEMORY_HEAP_INITIALSIZE ).envName, "2000m")
                .withEnv(confNames.get( Setting.MEMORY_HEAP_MAXSIZE ).envName, "3000m")
                .withEnv( confNames.get( Setting.DIRECTORIES_LOGS ).envName, "/notdefaultlogs" )
                .withEnv( confNames.get( Setting.DIRECTORIES_DATA ).envName, "/notdefaultdata" ) )
        {
            Path confMount = HostFileSystemOperations.createTempFolderAndMountAsVolume(
                    container,
                    "overriddenbyenv-conf-",
                    "/conf" );
            conf = confMount.resolve( "neo4j.conf" ).toFile();
            makeContainerDumpConfig( container );
            container.start();
        }

        // now check the settings we set via env are in the new conf file
        Assertions.assertTrue( conf.exists(), "configuration file not written" );
        Assertions.assertTrue( conf.canRead(), "configuration file not readable for some reason?" );

        Map<String,String> configurations = parseConfFile( conf );
        Assertions.assertTrue( configurations.containsKey( confNames.get( Setting.MEMORY_PAGECACHE_SIZE ).name ),
                               "pagecache size not overridden" );
        Assertions.assertEquals( "1000m",
                                 configurations.get( confNames.get( Setting.MEMORY_PAGECACHE_SIZE ).name ),
                                 "pagecache size not overridden" );

        Assertions.assertTrue( configurations.containsKey( confNames.get( Setting.MEMORY_HEAP_INITIALSIZE ).name ),
                               "initial heap size not overridden" );
        Assertions.assertEquals( "2000m",
                                 configurations.get( confNames.get( Setting.MEMORY_HEAP_INITIALSIZE ).name ),
                                 "initial heap size not overridden" );

        Assertions.assertTrue( configurations.containsKey( confNames.get( Setting.MEMORY_HEAP_MAXSIZE ).name ),
                               "maximum heap size not overridden" );
        Assertions.assertEquals( "3000m",
                                 configurations.get( confNames.get( Setting.MEMORY_HEAP_MAXSIZE ).name ),
                                 "maximum heap size not overridden" );

        Assertions.assertTrue( configurations.containsKey( confNames.get( Setting.DIRECTORIES_LOGS ).name ),
                               "log folder not overridden" );
        Assertions.assertEquals( "/notdefaultlogs",
                                 configurations.get(confNames.get( Setting.DIRECTORIES_LOGS ).name),
                                 "log directory not overridden" );
        Assertions.assertTrue( configurations.containsKey( confNames.get( Setting.DIRECTORIES_DATA ).name ), "data folder not overridden" );
        Assertions.assertEquals( "/notdefaultdata",
                                 configurations.get(confNames.get( Setting.DIRECTORIES_DATA ).name),
                                 "data directory not overridden" );
    }

    @Test
    void testReadsTheConfFile() throws Exception
    {
        Path debugLog;

        try(GenericContainer container = createContainer())
        {
			Path testOutputFolder = HostFileSystemOperations.createTempFolder( "confIsRead-" );
            //Mount /conf
            Path confMount = HostFileSystemOperations.createTempFolderAndMountAsVolume(
            		container,
					"conf-",
					"/conf",
					testOutputFolder);
            Path logMount = HostFileSystemOperations.createTempFolderAndMountAsVolume(
            		container,
					"logs-",
					"/logs",
					testOutputFolder);
            debugLog = logMount.resolve("debug.log");
            SetContainerUser.nonRootUser( container );
            //Create ReadConf.conf file with the custom env variables
            Path confFile = confFolder.resolve( "ReadConf.conf" );
            Files.copy( confFile, confMount.resolve( "neo4j.conf" ) );
            //Start the container
            makeContainerWaitForNeo4jReady( container );
            container.start();
        }

        //Check if the container reads the conf file
        assertConfigurationPresentInDebugLog( debugLog, confNames.get( Setting.MEMORY_HEAP_MAXSIZE ),
                                              "512", true );
    }

    @Test
    void testDefaultsConfigsAreSet() throws Exception
    {
        try(GenericContainer container = createContainer())
        {
            //Mount /logs
            Path logMount = HostFileSystemOperations.createTempFolderAndMountAsVolume(
                    container,
                    "default-settings-logs-",
                    "/logs" );
            SetContainerUser.nonRootUser( container );
            //Start the container
            makeContainerWaitForNeo4jReady( container );
            container.start();
            DatabaseIO dbio = new DatabaseIO( container );

            dbio.verifyConfigurationSetting("neo4j", "none", confNames.get( Setting.DEFAULT_LISTEN_ADDRESS).name, "0.0.0.0");
            assertConfigurationPresentInDebugLog(logMount.resolve("debug.log"), confNames.get( Setting.DEFAULT_LISTEN_ADDRESS), "0.0.0.0", true);

            // test enterprise only default configurations are set
            if (TestSettings.EDITION == TestSettings.Edition.ENTERPRISE) {
                String expectedDiscoveryAddress = container.getContainerId().substring(0, 12) + ":5000";
                String expectedTxAddress = container.getContainerId().substring(0, 12) + ":6000";
                String expectedRaftAddress = container.getContainerId().substring(0, 12) + ":7000";
                dbio.verifyConfigurationSetting("neo4j", "none", confNames.get( Setting.CLUSTER_DISCOVERY_ADDRESS).name, expectedDiscoveryAddress);
                assertConfigurationPresentInDebugLog(logMount.resolve("debug.log"), confNames.get( Setting.CLUSTER_DISCOVERY_ADDRESS), expectedDiscoveryAddress,true);
                dbio.verifyConfigurationSetting("neo4j", "none", confNames.get( Setting.CLUSTER_TRANSACTION_ADDRESS).name, expectedTxAddress);
                assertConfigurationPresentInDebugLog(logMount.resolve("debug.log"), confNames.get( Setting.CLUSTER_TRANSACTION_ADDRESS), expectedTxAddress,true);
                dbio.verifyConfigurationSetting("neo4j", "none", confNames.get( Setting.CLUSTER_RAFT_ADDRESS).name, expectedRaftAddress);
                assertConfigurationPresentInDebugLog(logMount.resolve("debug.log"), confNames.get( Setting.CLUSTER_RAFT_ADDRESS), expectedRaftAddress,true);

                dbio.verifyConfigurationSetting("neo4j", "none", confNames.get( Setting.TXLOG_RETENTION_POLICY).name, "100M size");
                assertConfigurationPresentInDebugLog(logMount.resolve("debug.log"), confNames.get( Setting.TXLOG_RETENTION_POLICY), "100M size", true);
            }
        }
    }

    @Test
    void testCommentedConfigsAreReplacedByDefaultOnes() throws Exception
    {
        File conf;
        try(GenericContainer container = createContainer())
        {
            //Mount /conf
            Path confMount = HostFileSystemOperations.createTempFolderAndMountAsVolume(
            		container,
					"replacedbydefault-conf-",
					"/conf" );
            conf = confMount.resolve( "neo4j.conf" ).toFile();
            SetContainerUser.nonRootUser( container );
            //Create ConfsReplaced.conf file
            Path confFile = confFolder.resolve( "ConfsReplaced.conf" );
            Files.copy( confFile, confMount.resolve( "neo4j.conf" ) );
            makeContainerDumpConfig( container );
            //Start the container
            container.start();
        }
        //Read the config file to check if the config is set correctly
        Map<String,String> configurations = parseConfFile( conf );
        Assertions.assertTrue( configurations.containsKey( confNames.get( Setting.MEMORY_PAGECACHE_SIZE ).name ),
                               "conf settings not set correctly by docker-entrypoint" );
        Assertions.assertEquals( "512M",
                                 configurations.get(confNames.get( Setting.MEMORY_PAGECACHE_SIZE ).name),
                                 "conf settings not appended correctly by docker-entrypoint" );
    }

    @Test
    void testConfigsAreNotOverridenByDockerentrypoint() throws Exception
    {
        File conf;
        try(GenericContainer container = createContainer())
        {
            //Mount /conf
            Path confMount = HostFileSystemOperations.createTempFolderAndMountAsVolume(
            		container,
					"notoverriddenbydefault-conf-",
					"/conf" );
            conf = confMount.resolve( "neo4j.conf" ).toFile();
            SetContainerUser.nonRootUser( container );
            //Create ConfsNotOverridden.conf file
            Path confFile = confFolder.resolve( "ConfsNotOverridden.conf" );
            Files.copy( confFile, confMount.resolve( "neo4j.conf" ) );
            makeContainerDumpConfig( container );
            container.start();
        }

        //Read the config file to check if the config is not overriden
        Map<String, String> configurations = parseConfFile(conf);
        Assertions.assertTrue(configurations.containsKey(confNames.get( Setting.MEMORY_PAGECACHE_SIZE).name),
                              "conf settings not set correctly by docker-entrypoint");
        Assertions.assertEquals("1024M",
                                configurations.get(confNames.get( Setting.MEMORY_PAGECACHE_SIZE).name),
                                "docker-entrypoint has overridden custom setting set from user's conf");
    }

    @Test
    void testEnvVarsOverride() throws Exception
    {
        Path debugLog;
        try(GenericContainer container = createContainer().withEnv(confNames.get(Setting.MEMORY_PAGECACHE_SIZE).envName, "512m"))
        {
			Path testOutputFolder = HostFileSystemOperations.createTempFolder( "envoverrideworks-" );
            Path confMount = HostFileSystemOperations.createTempFolderAndMountAsVolume(
					container,
					"conf-",
					"/conf",
					testOutputFolder );
            Path logMount = HostFileSystemOperations.createTempFolderAndMountAsVolume(
					container,
					"logs-",
					"/logs",
					testOutputFolder );
            debugLog = logMount.resolve( "debug.log" );
            SetContainerUser.nonRootUser( container );
            //Create EnvVarsOverride.conf file
            Path confFile = confFolder.resolve("EnvVarsOverride.conf");
            Files.copy( confFile, confMount.resolve( "neo4j.conf" ) );
            //Start the container
            makeContainerWaitForNeo4jReady( container );
            container.start();
        }

        assertConfigurationPresentInDebugLog(debugLog, confNames.get(Setting.MEMORY_PAGECACHE_SIZE),
                                             new String[]{"512m", "512.00MiB"}, true );
    }

    @Test
    void testEnterpriseOnlyDefaultsDontOverrideConfFile() throws Exception
    {
        Assumptions.assumeTrue(TestSettings.EDITION == TestSettings.Edition.ENTERPRISE,
                "This is testing only ENTERPRISE EDITION configs");

        try(GenericContainer container = createContainer())
        {
            Path testOutputFolder = HostFileSystemOperations.createTempFolder( "ee-only-not-ovewritten-" );
            Path confMount = HostFileSystemOperations.createTempFolderAndMountAsVolume(
                    container,
                    "conf-",
                    "/conf",
                    testOutputFolder );
            Path logMount = HostFileSystemOperations.createTempFolderAndMountAsVolume(
                    container,
                    "logs-",
                    "/logs",
                    testOutputFolder );
            // mount a configuration file with enterprise only settings already set
            Path confFile = confFolder.resolve( "EnterpriseOnlyNotOverwritten.conf" );
            Files.copy( confFile, confMount.resolve( "neo4j.conf" ) );

            //Start the container
            SetContainerUser.nonRootUser( container );
            makeContainerWaitForNeo4jReady( container );
            container.start();
            //Read debug.log to check that cluster confs are set successfully
            assertConfigurationPresentInDebugLog( logMount.resolve( "debug.log" ),
                                                  confNames.get( Setting.CLUSTER_TRANSACTION_ADDRESS ),
                                                  "localhost:6060", true );
        }
    }

    @Test
    void testMountingMetricsFolderShouldNotSetConfInCommunity() throws Exception
    {
        Assumptions.assumeTrue( TestSettings.EDITION == TestSettings.Edition.COMMUNITY,
                                "Test only valid with community edition");

        try ( GenericContainer container = createContainer() )
        {
            Path testOutputFolder = HostFileSystemOperations.createTempFolder( "metrics-mounting-" );
            HostFileSystemOperations.createTempFolderAndMountAsVolume( container,
                                                                       "metrics-",
                                                                       "/metrics",
                                                                       testOutputFolder );
            Path confMount = HostFileSystemOperations.createTempFolderAndMountAsVolume( container,
                                                                                        "conf-",
                                                                                        "/conf",
                                                                                        testOutputFolder );
            makeContainerDumpConfig( container );
            container.start();

            File conf = confMount.resolve( "neo4j.conf" ).toFile();
            Map<String, String> configurations = parseConfFile(conf);
            Assertions.assertFalse(configurations.containsKey(confNames.get( Setting.DIRECTORIES_METRICS ).name),
                                   "should not be setting any metrics configurations in community edition");
        }
    }

    @Test
    void testCommunityDoesNotHaveEnterpriseConfigs() throws Exception
    {
        Assumptions.assumeTrue(TestSettings.EDITION == TestSettings.Edition.COMMUNITY,
                               "This is testing only COMMUNITY EDITION configs");

        Path debugLog;
        try(GenericContainer container = createContainer().withEnv(confNames.get(Setting.MEMORY_PAGECACHE_SIZE).envName, "512m"))
        {
            //Mount /logs
			Path logMount = HostFileSystemOperations.createTempFolderAndMountAsVolume(
					container,
					"enterprisesettingsnotincommunity-logs-",
					"/logs" );
            debugLog = logMount.resolve( "debug.log" );
            SetContainerUser.nonRootUser( container );
            //Start the container
            makeContainerWaitForNeo4jReady( container );
            container.start();
        }

        //Read debug.log to check that cluster confs are not present
        assertConfigurationPresentInDebugLog( debugLog, confNames.get(Setting.CLUSTER_TRANSACTION_ADDRESS), "*", false );
    }

    @Test
    void testJvmAdditionalNotOverridden() throws Exception
    {
        Assumptions.assumeTrue( TestSettings.NEO4J_VERSION.isAtLeastVersion( Neo4jVersion.NEO4J_VERSION_400),
                                 "test not applicable in versions older than 4.0." );
        Path logMount;
        String expectedJvmAdditional = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005";

        try(GenericContainer container = createContainer())
        {
			Path testOutputFolder = HostFileSystemOperations.createTempFolder( "jvmaddnotoverridden-" );
            //Mount /conf
			Path confMount = HostFileSystemOperations.createTempFolderAndMountAsVolume(
					container,
					"conf-",
					"/conf", testOutputFolder);
			logMount = HostFileSystemOperations.createTempFolderAndMountAsVolume(
					container,
					"logs-",
					"/logs",
					testOutputFolder);
			SetContainerUser.nonRootUser( container );
            //Create JvmAdditionalNotOverridden.conf file
            Path confFile = confFolder.resolve( "JvmAdditionalNotOverridden.conf" );
            Files.copy( confFile, confMount.resolve( "neo4j.conf" ) );
            //Start the container
            makeContainerWaitForNeo4jReady( container );
            container.start();
            // verify setting correctly loaded into neo4j
            DatabaseIO dbio = new DatabaseIO( container );
            dbio.verifyConfigurationSetting( "neo4j", "none",
                                             confNames.get( Setting.JVM_ADDITIONAL ).name, expectedJvmAdditional);
        }

        assertConfigurationPresentInDebugLog( logMount.resolve( "debug.log"),
                                              confNames.get( Setting.JVM_ADDITIONAL ),
                                              expectedJvmAdditional,
                                              true );
    }

    @Test
    void testDollarInConfigEscapedProperly_conf() throws Exception
    {
        Assumptions.assumeTrue( TestSettings.NEO4J_VERSION.isAtLeastVersion( new Neo4jVersion( 4,3,0 ) ),
                                "test not applicable in versions before 4.3." );
        String expectedJvmAdditional = "-Djavax.net.ssl.trustStorePassword=beepbeep$boop1boop2";
        Path logMount;
        try(GenericContainer container = createContainer())
        {
            Path testOutputFolder = HostFileSystemOperations.createTempFolder( "jvmdollarinconf-" );
            //Mount /conf
            Path confMount = HostFileSystemOperations.createTempFolderAndMountAsVolume(
                    container,
                    "conf-",
                    "/conf", testOutputFolder);
            logMount = HostFileSystemOperations.createTempFolderAndMountAsVolume(
                    container,
                    "logs-",
                    "/logs",
                    testOutputFolder);
            SetContainerUser.nonRootUser( container );
            //copy test conf file
            Path confFile = confFolder.resolve("JvmAdditionalWithDollar.conf" );
            Files.copy( confFile, confMount.resolve( "neo4j.conf" ) );
            //Start the container
            makeContainerWaitForNeo4jReady( container );
            container.start();
            // verify setting correctly loaded into neo4j
            DatabaseIO dbio = new DatabaseIO( container );
            dbio.verifyConfigurationSetting( "neo4j", "none",
                                             confNames.get( Setting.JVM_ADDITIONAL ).name, expectedJvmAdditional);
        }

        assertConfigurationPresentInDebugLog( logMount.resolve( "debug.log"),
                                              confNames.get( Setting.JVM_ADDITIONAL ),
                                              expectedJvmAdditional,
                                              true );
    }

    @Test
    void testDollarInConfigEscapedProperly_env() throws Exception
    {
        Assumptions.assumeTrue( TestSettings.NEO4J_VERSION.isAtLeastVersion( new Neo4jVersion( 4,3,0 ) ),
                                "test not applicable in versions before 4.3." );
        Path logMount;
        String expectedJvmAdditional = "-Djavax.net.ssl.trustStorePassword=bleepblorp$bleep1blorp4";
        try(GenericContainer container = createContainer())
        {
            logMount = HostFileSystemOperations.createTempFolderAndMountAsVolume(
                    container,
                    "confdollarlogs-",
                    "/logs");
            SetContainerUser.nonRootUser( container );
            container.withEnv( confNames.get( Setting.JVM_ADDITIONAL ).envName, expectedJvmAdditional);
            //Start the container
            makeContainerWaitForNeo4jReady( container );
            container.start();
            // verify setting correctly loaded into neo4j
            DatabaseIO dbio = new DatabaseIO( container );
            dbio.verifyConfigurationSetting( "neo4j", "none",
                                             confNames.get( Setting.JVM_ADDITIONAL ).name, expectedJvmAdditional);
        }

        assertConfigurationPresentInDebugLog( logMount.resolve( "debug.log"),
                                              confNames.get( Setting.JVM_ADDITIONAL ),
                                              expectedJvmAdditional,
                                              true );
    }

    @Test
    void testShellExpansionAvoided() throws Exception
    {
        Assumptions.assumeTrue( TestSettings.NEO4J_VERSION.isAtLeastVersion( Neo4jVersion.NEO4J_VERSION_400),
                                "test only applicable to 4.0 and beyond." );

        Path confMount;
        try(GenericContainer container = createContainer()
                .withEnv(confNames.get(Setting.SECURITY_PROCEDURES_UNRESTRICTED).envName, "*"))
        {
			confMount = HostFileSystemOperations.createTempFolderAndMountAsVolume(
					container,
					"shellexpansionavoided-conf-",
					"/conf" );
            makeContainerDumpConfig( container );
            container.start();
        }
        File conf = confMount.resolve( "neo4j.conf" ).toFile();
        Map<String, String> configurations = parseConfFile(conf);
        Assertions.assertTrue(configurations.containsKey(confNames.get( Setting.SECURITY_PROCEDURES_UNRESTRICTED).name),
                              "configuration not set from env var");
        Assertions.assertEquals("*",
                configurations.get(confNames.get( Setting.SECURITY_PROCEDURES_UNRESTRICTED).name),
                "Configuration value should be *. If it's not docker-entrypoint.sh probably evaluated it as a glob expression.");
    }
}