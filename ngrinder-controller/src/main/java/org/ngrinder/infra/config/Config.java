  /*
 * Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ngrinder.infra.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import net.grinder.util.ListenerSupport;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;
import org.ngrinder.common.constant.ClusterConstants;
import org.ngrinder.common.constant.ControllerConstants;
import org.ngrinder.common.constants.InternalConstants;
import org.ngrinder.common.exception.ConfigurationException;
import org.ngrinder.common.exception.NGrinderRuntimeException;
import org.ngrinder.common.model.Home;
import org.ngrinder.common.util.FileWatchdog;
import org.ngrinder.common.util.PropertiesKeyMapper;
import org.ngrinder.common.util.PropertiesWrapper;
import org.ngrinder.infra.logger.CoreLogger;
import org.ngrinder.infra.spring.SpringContext;
import org.ngrinder.service.AbstractConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.Map;
import java.util.Properties;

import static java.nio.charset.StandardCharsets.UTF_8;
import static net.grinder.util.NoOp.noOp;
import static org.apache.commons.io.FileUtils.*;
import static org.apache.commons.lang.StringUtils.defaultIfEmpty;
import static org.apache.commons.lang.StringUtils.isEmpty;
import static org.ngrinder.common.constant.CacheConstants.REGION_ATTR_KEY;
import static org.ngrinder.common.constant.CacheConstants.SUBREGION_ATTR_KEY;
import static org.ngrinder.common.constant.DatabaseConstants.PROP_DATABASE_UNIT_TEST;
import static org.ngrinder.common.constants.GrinderConstants.GRINDER_SECURITY_LEVEL_NORMAL;
import static org.ngrinder.common.model.Home.PATH_SCRIPT_TEMPLATE_DIRECTORY;
import static org.ngrinder.common.util.CollectionUtils.newHashMap;
import static org.ngrinder.common.util.FileUtils.copyResourceToFile;
import static org.ngrinder.common.util.PathUtils.getSubPath;
import static org.ngrinder.common.util.Preconditions.checkNotNull;

/**
 * Spring component which is responsible to get the nGrinder configurations which is stored ${NGRINDER_HOME}.
 *
 * @since 3.0
 */

@Component
public class Config extends AbstractConfig implements ControllerConstants, ClusterConstants {
	public static final String NONE_REGION = "NONE";
	private static final String NGRINDER_DEFAULT_FOLDER = ".ngrinder";
	private static final String NGRINDER_EX_FOLDER = ".ngrinder_ex";
	private static final Logger LOG = LoggerFactory.getLogger(Config.class);

	private final ListenerSupport<PropertyChangeListener> systemConfListeners = new ListenerSupport<>();

	private Home home = null;
	private Home exHome = null;
	private PropertiesWrapper internalProperties;
	private PropertiesWrapper controllerProperties;
	private PropertiesWrapper databaseProperties;
	private PropertiesWrapper clusterProperties;
	private PropertiesWrapper ldapProperties;
	private String announcement = "";
	private Date announcementDate;
	private boolean verbose;
	private boolean cluster;

	protected PropertiesKeyMapper internalPropertiesKeyMapper = PropertiesKeyMapper.create("internal-properties.map");
	protected PropertiesKeyMapper databasePropertiesKeyMapper = PropertiesKeyMapper.create("database-properties.map");
	protected PropertiesKeyMapper controllerPropertiesKeyMapper = PropertiesKeyMapper.create("controller-properties.map");
	protected PropertiesKeyMapper clusterPropertiesKeyMapper = PropertiesKeyMapper.create("cluster-properties.map");
	protected PropertiesKeyMapper ldapPropertiesKeyMapper = PropertiesKeyMapper.create("ldap-properties.map");

	@SuppressWarnings("SpringJavaAutowiringInspection")
	@Autowired
	private SpringContext context;

	/**
	 * Make it singleton.
	 */
	Config() {
	}

	/**
	 * Add the system configuration change listener.
	 *
	 * @param listener listener
	 */
	@SuppressWarnings("UnusedDeclaration")
	public void addSystemConfListener(PropertyChangeListener listener) {
		systemConfListeners.add(listener);
	}

	/**
	 * Initialize the {@link Config} object.
	 * <p/>
	 * This method mainly resolves ${NGRINDER_HOME} and loads system properties. In addition, the logger is initialized
	 * and the default configuration files are copied into ${NGRINDER_HOME} if they do not exists.
	 */
	@PostConstruct
	public void init() {
		try {
			CoreLogger.LOGGER.info("nGrinder is starting...");
			home = resolveHome();
			home.init();
			exHome = resolveExHome();
			copyDefaultConfigurationFiles();
			copyScriptTemplates();
			loadInternalProperties();
			loadProperties();
			initHomeMonitor();
			// Load cluster in advance. cluster mode is not dynamically
			// reloadable.
			cluster = resolveClusterMode();
			initDevModeProperties();
			loadAnnouncement();
			loadDatabaseProperties();
		} catch (IOException e) {
			throw new ConfigurationException("Error while init nGrinder", e);
		}
	}

	protected void initDevModeProperties() {
		if (!isDevMode()) {
			initLogger(false);
		} else {
			final PropertiesWrapper controllerProperties = getControllerProperties();
			controllerProperties.addProperty(PROP_CONTROLLER_AGENT_FORCE_UPDATE, "true");
			controllerProperties.addProperty(PROP_CONTROLLER_ENABLE_AGENT_AUTO_APPROVAL, "true");
			controllerProperties.addProperty(PROP_CONTROLLER_ENABLE_SCRIPT_CONSOLE, "true");
		}
	}

	private boolean resolveClusterMode() {
		String mode = getClusterMode();
		return !"none".equals(mode) || getClusterProperties().getPropertyBoolean(PROP_CLUSTER_ENABLED);
	}

	public String getClusterMode() {
		return getClusterProperties().getProperty(PROP_CLUSTER_MODE, "none");
	}

	/**
	 * Destroy bean.
	 */
	@PreDestroy
	public void destroy() {
		// Stop all the non-daemon thread.
		announcementWatchDog.interrupt();
		systemConfWatchDog.interrupt();
		policyJsWatchDog.interrupt();
	}

	/**
	 * Get if the cluster mode is enable or not.
	 *
	 * @return true if the cluster mode is enabled.
	 * @since 3.1
	 */
	public boolean isClustered() {
		return cluster;
	}

	public int getControllerPort() {
		return getControllerProperties().getPropertyInt(ControllerConstants.PROP_CONTROLLER_CONTROLLER_PORT);
	}

	/**
	 * Get the ngrinder instance IPs consisting of the current cluster from the configuration.
	 *
	 * @return ngrinder instance IPs
	 */
	public String[] getClusterURIs() {
		String clusterUri = getClusterProperties().getProperty(PROP_CLUSTER_MEMBERS);
		return StringUtils.split(StringUtils.trimToEmpty(clusterUri), ",;");
	}

	/**
	 * Get the current region from the configuration.
	 *
	 * @return region. If it's not clustered mode, return "NONE"
	 */
	public String getRegion() {
		return isClustered() ? getClusterProperties().getProperty(PROP_CLUSTER_REGION) : NONE_REGION;
	}

	/**
	 * Get the current region and subregion from the configuration.
	 *
	 * @return region and subregion. If it's not clustered mode, return "NONE"
	 */
	public Map<String, String> getRegionWithSubregion() {
		Map<String, String> region = newHashMap();
		region.put(REGION_ATTR_KEY, getRegion());

		if (isClustered()) {
			region.put(SUBREGION_ATTR_KEY, defaultIfEmpty(getClusterProperties().getProperty(PROP_CLUSTER_SUBREGION), ""));
		}

		return region;
	}

	/**
	 * Get the monitor listener port from the configuration.
	 *
	 * @return monitor port
	 */
	public int getMonitorPort() {
		return getControllerProperties().getPropertyInt(PROP_CONTROLLER_MONITOR_PORT);
	}

	/**
	 * Check if the periodic usage report is enabled.
	 *
	 * @return true if enabled.
	 */
	@SuppressWarnings("unused")
	public boolean isUsageReportEnabled() {
		return getControllerProperties().getPropertyBoolean(PROP_CONTROLLER_USAGE_REPORT);
	}

	/**
	 * Check if user self-registration is enabled.
	 *
	 * @return true if enabled.
	 */
	public boolean isSignUpEnabled() {
		return getControllerProperties().getPropertyBoolean(PROP_CONTROLLER_ALLOW_SIGN_UP);
	}

	/**
	 * Initialize Logger.
	 *
	 * @param forceToVerbose true to force verbose logging.
	 */
	public synchronized void initLogger(boolean forceToVerbose) {
		setupLogger((forceToVerbose) || getControllerProperties().getPropertyBoolean(PROP_CONTROLLER_VERBOSE));
	}

	/**
	 * Set up the logger.
	 *
	 * @param verbose verbose mode?
	 */
	protected void setupLogger(boolean verbose) {
		this.verbose = verbose;
		final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
		context.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(verbose ? Level.DEBUG : Level.INFO);
	}

	/**
	 * Copy the default files and create default directories to ${NGRINDER_HOME}.
	 *
	 * @throws IOException occurs when there is no such a files.
	 */
	protected void copyDefaultConfigurationFiles() throws IOException {
		checkNotNull(home);
		ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
		Resource[] resources = resolver.getResources("classpath*:ngrinder_home_template/*");
		home.copyFrom(resources);
	}

	private void copyScriptTemplates() {
		File scriptTemplateDirectory = home.getScriptTemplateDirectory();
		if (!scriptTemplateDirectory.exists()) {
			try {
				ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
				Resource[] resources = resolver.getResources("classpath*:script_template/**/*.*");
				for (Resource resource : resources) {
					String scriptTemplatePath = home.getScriptTemplateDirectory().getAbsolutePath();
					scriptTemplatePath += getSubPath(PATH_SCRIPT_TEMPLATE_DIRECTORY, resource.getURL().getPath());
					copyResourceToFile(resource, new File(scriptTemplatePath));
				}
			} catch (IOException e) {
				throw new NGrinderRuntimeException("Error while copying script templates.", e);
			}
		}
	}

	public File getHomeScriptTemplateDirectory() {
		File scriptTemplateDirectory = home.getScriptTemplateDirectory();
		if (!scriptTemplateDirectory.exists()) {
			copyScriptTemplates();
		}
		return scriptTemplateDirectory;
	}

	/**
	 * Resolve nGrinder home path.
	 *
	 * @return resolved home
	 */
	protected Home resolveHome() {
		if (StringUtils.isNotBlank(System.getProperty("unit-test"))) {
			final String tempDir = System.getProperty("java.io.tmpdir");
			final File tmpHome = new File(tempDir, ".ngrinder");
			if (tmpHome.mkdirs()) {
				LOG.info("{} is created", tmpHome.getPath());
			}
			try {
				FileUtils.forceDeleteOnExit(tmpHome);
			} catch (IOException e) {
				LOG.error("Error while setting forceDeleteOnExit on {}", tmpHome);
			}
			return new Home(tmpHome);
		}

		File homeDirectory = new File(getUserHome());
		return new Home(homeDirectory);
	}

	public static String getCurrentLibPath() {
		return ApplicationContext.class.getProtectionDomain().getCodeSource().getLocation().getPath();
	}

	public static String getUserHome() {
		String userHomeFromEnv = System.getenv("NGRINDER_HOME");
		String userHomeFromProperty = System.getProperty("ngrinder.home");
		if (!StringUtils.equals(userHomeFromEnv, userHomeFromProperty)) {
			CoreLogger.LOGGER.warn("The path to ngrinder-home is ambiguous:");
			CoreLogger.LOGGER.warn("    System Environment:  NGRINDER_HOME=" + userHomeFromEnv);
			CoreLogger.LOGGER.warn("    Java System Property:  ngrinder.home=" + userHomeFromProperty);
			CoreLogger.LOGGER.warn("    '" + userHomeFromProperty + "' is accepted.");
		}
		String userHome = StringUtils.defaultIfEmpty(userHomeFromProperty, userHomeFromEnv);

		if (isEmpty(userHome)) {
			userHome = System.getProperty("user.home") + File.separator + NGRINDER_DEFAULT_FOLDER;
		} else if (StringUtils.startsWith(userHome, "~" + File.separator)) {
			userHome = System.getProperty("user.home") + File.separator + userHome.substring(2);
		} else if (StringUtils.startsWith(userHome, "." + File.separator)) {
			userHome = System.getProperty("user.dir") + File.separator + userHome.substring(2);
		}
		return FilenameUtils.normalize(userHome);
	}

	/**
	 * Resolve nGrinder extended home path.
	 *
	 * @return resolved home
	 */
	protected Home resolveExHome() {
		String exHomeFromEnv = System.getenv("NGRINDER_EX_HOME");
		String exHomeFromProperty = System.getProperty("ngrinder.ex_home");
		if (!StringUtils.equals(exHomeFromEnv, exHomeFromProperty)) {
			CoreLogger.LOGGER.warn("The path to ngrinder ex home is ambiguous:");
			CoreLogger.LOGGER.warn("    System Environment:  NGRINDER_EX_HOME=" + exHomeFromEnv);
			CoreLogger.LOGGER.warn("    Java System Property:  ngrinder.ex_home=" + exHomeFromProperty);
			CoreLogger.LOGGER.warn("    '" + exHomeFromProperty + "' is accepted.");
		}
		String userHome = StringUtils.defaultIfEmpty(exHomeFromProperty, exHomeFromEnv);

		if (isEmpty(userHome)) {
			userHome = System.getProperty("user.home") + File.separator + NGRINDER_EX_FOLDER;
		} else if (StringUtils.startsWith(userHome, "~" + File.separator)) {
			userHome = System.getProperty("user.home") + File.separator + userHome.substring(2);
		} else if (StringUtils.startsWith(userHome, "." + File.separator)) {
			userHome = System.getProperty("user.dir") + File.separator + userHome.substring(2);
		}

		userHome = FilenameUtils.normalize(userHome);
		File exHomeDirectory = new File(userHome);
		CoreLogger.LOGGER.info("nGrinder ex home directory:{}.", exHomeDirectory);
		try {
			//noinspection ResultOfMethodCallIgnored
			exHomeDirectory.mkdirs();
		} catch (Exception e) {
			// If it's not possible.. do it... without real directory.
			noOp();
		}
		return new Home(exHomeDirectory, false);
	}

	/**
	 * Load internal properties which is not modifiable by user.
	 */
	protected void loadInternalProperties() {
		Properties properties = new Properties();
		try (InputStream inputStream = new ClassPathResource("/internal.properties").getInputStream()) {
			properties.load(inputStream);
			internalProperties = new PropertiesWrapper(properties, internalPropertiesKeyMapper);
		} catch (IOException e) {
			CoreLogger.LOGGER.error("Error while load internal.properties", e);
			internalProperties = new PropertiesWrapper(properties, internalPropertiesKeyMapper);
		}
	}

	/**
	 * Load database related properties. (database.conf)
	 */
	protected void loadDatabaseProperties() {
		checkNotNull(home);
		Properties properties = home.getProperties("database.conf");
		properties.put("NGRINDER_HOME", home.getDirectory().getAbsolutePath());
		properties.putAll(System.getProperties());
		databaseProperties = new PropertiesWrapper(properties, databasePropertiesKeyMapper);
	}

	/**
	 * Load system related properties. (system.conf)
	 */
	public synchronized void loadProperties() {
		Properties properties = checkNotNull(home).getProperties("system.conf");
		properties.put("NGRINDER_HOME", home.getDirectory().getAbsolutePath());
		if (exHome.exists()) {
			Properties exProperties = exHome.getProperties("system-ex.conf");
			if (exProperties.isEmpty()) {
				exProperties = exHome.getProperties("system.conf");
			}
			properties.putAll(exProperties);
		}
		properties.putAll(System.getProperties());
		// Override if exists
		controllerProperties = new PropertiesWrapper(properties, controllerPropertiesKeyMapper);
		clusterProperties = new PropertiesWrapper(properties, clusterPropertiesKeyMapper);
		ldapProperties = new PropertiesWrapper(properties, ldapPropertiesKeyMapper);
	}

	/**
	 * Load the announcement content.
	 */
	@SuppressWarnings("SynchronizeOnNonFinalField")
	public void loadAnnouncement() {
		checkNotNull(home);
		synchronized (announcement) {
			File sysFile = home.getSubFile("announcement.conf");
			try {
				announcement = readFileToString(sysFile, "UTF-8");
				if (sysFile.exists()) {
					announcementDate = new Date(sysFile.lastModified());
				} else {
					announcementDate = null;
				}
			} catch (IOException e) {
				CoreLogger.LOGGER.error("Error while reading announcement file.", e);
				announcement = "";
			}
		}
	}

	/**
	 * watch docs.
	 */
	private FileWatchdog announcementWatchDog;
	private FileWatchdog systemConfWatchDog;
	private FileWatchdog policyJsWatchDog;

	protected void initHomeMonitor() {
		checkNotNull(home);
		this.announcementWatchDog = new FileWatchdog(getHome().getSubFile("announcement.conf").getAbsolutePath()) {
			@Override
			protected void doOnChange() {
				CoreLogger.LOGGER.info("Announcement file is changed.");
				loadAnnouncement();
			}
		};
		announcementWatchDog.setName("WatchDog - announcement.conf");
		announcementWatchDog.setDelay(2000);
		announcementWatchDog.start();
		this.systemConfWatchDog = new FileWatchdog(getHome().getSubFile("system.conf").getAbsolutePath()) {
			@Override
			protected void doOnChange() {
				try {
					CoreLogger.LOGGER.info("System configuration(system.conf) is changed.");
					loadProperties();
					systemConfListeners.apply(listener -> {
						listener.propertyChange(null);
					});
					CoreLogger.LOGGER.info("New system configuration is applied.");
				} catch (Exception e) {
					CoreLogger.LOGGER.error("Error occurs while applying new system configuration", e);
				}

			}
		};
		systemConfWatchDog.setName("WatchDoc - system.conf");
		systemConfWatchDog.setDelay(2000);
		systemConfWatchDog.start();

		String processThreadPolicyPath = getHome().getSubFile("process_and_thread_policy.js").getAbsolutePath();
		this.policyJsWatchDog = new FileWatchdog(processThreadPolicyPath) {
			@Override
			protected void doOnChange() {
				CoreLogger.LOGGER.info("process_and_thread_policy file is changed.");
				policyScript = "";
			}
		};
		policyJsWatchDog.setName("WatchDoc - process_and_thread_policy.js");
		policyJsWatchDog.setDelay(2000);
		policyJsWatchDog.start();
	}

	/**
	 * Get the database properties.
	 *
	 * @return database properties
	 */
	public PropertiesWrapper getDatabaseProperties() {
		checkNotNull(databaseProperties);
		if (context.isUnitTestContext() && !databaseProperties.exist(PROP_DATABASE_UNIT_TEST)) {
			databaseProperties.addProperty(PROP_DATABASE_UNIT_TEST, "true");
		}
		return databaseProperties;
	}

	/**
	 * Check if it's test mode.
	 *
	 * @return true if test mode
	 */
	public boolean isDevMode() {
		return getControllerProperties().getPropertyBoolean(PROP_CONTROLLER_DEV_MODE);
	}

	/**
	 * Check if the user security is enabled.
	 *
	 * @return true if user security is enabled.
	 */
	public boolean isUserSecurityEnabled() {
		return getControllerProperties().getPropertyBoolean(PROP_CONTROLLER_USER_SECURITY);
	}

	/**
	 * Check if it's the security enabled mode.
	 *
	 * @return true if security is enabled.
	 */
	public boolean isSecurityEnabled() {
		return !isDevMode() && getControllerProperties().getPropertyBoolean(PROP_CONTROLLER_SECURITY);
	}

	/**
	 * Get system security level from system properties.
	 *
	 * @return security level.
	 */
	public String getSecurityLevel() {
		return getControllerProperties().getProperty(PROP_CONTROLLER_SECURITY_LEVEL, GRINDER_SECURITY_LEVEL_NORMAL);
	}

	/**
	 * Check if it is the demo mode.
	 *
	 * @return true if demo mode is enabled.
	 */
	public boolean isDemo() {
		return getControllerProperties().getPropertyBoolean(PROP_CONTROLLER_DEMO_MODE);
	}

	/**
	 * Check if the plugin support is enabled.
	 * <p/>
	 * The reason why we need this configuration is that it takes time to initialize plugin system in unit test context.
	 *
	 * @return true if the plugin is supported.
	 */
	public boolean isPluginSupported() {
		return (getControllerProperties().getPropertyBoolean(PROP_CONTROLLER_PLUGIN_SUPPORT));
	}

	/**
	 * Get the resolved home folder.
	 *
	 * @return home
	 */
	public Home getHome() {
		return this.home;
	}

	/**
	 * Get the resolved extended home folder.
	 *
	 * @return home
	 * @since 3.1
	 */
	public Home getExHome() {
		return this.exHome;
	}

	/**
	 * Get the system properties.
	 *
	 * @return {@link PropertiesWrapper} which is loaded from system.conf.
	 */
	public PropertiesWrapper getControllerProperties() {
		return checkNotNull(controllerProperties);
	}

	/**
	 * Get the announcement content.
	 *
	 * @return loaded from announcement.conf.
	 */
	public String getAnnouncement() {
		return announcement;
	}

	/**
	 * Get the nGrinder version number.
	 *
	 * @return version number
	 */
	public String getVersion() {
		return getInternalProperties().getProperty(InternalConstants.PROP_INTERNAL_NGRINDER_VERSION);
	}

	/**
	 * Policy file which is used to determine the count of processes and threads.
	 */
	private String policyScript = "";

	/**
	 * Get the content of "process_and_thread_policy.js" file.
	 *
	 * @return loaded file content.
	 */
	public String getProcessAndThreadPolicyScript() {
		if (isEmpty(policyScript)) {
			try {
				policyScript = readFileToString(getHome().getSubFile("process_and_thread_policy.js"), UTF_8);
				return policyScript;
			} catch (IOException e) {
				LOG.error("Error while load process_and_thread_policy.js", e);
			}
		}
		return policyScript;
	}


	private String gitHubConfigTemplate = "";

	/**
	 * Get the content of "gitconfig-template.yml" file.
	 *
	 * @return loaded file content.
	 */
	public String getGitHubConfigTemplate() {
		if (isEmpty(gitHubConfigTemplate)) {
			try {
				gitHubConfigTemplate = readFileToString(getHome().getSubFile("gitconfig-template.yml"), UTF_8);
				return gitHubConfigTemplate;
			} catch (IOException e) {
				LOG.error("Error while load gitconfig-template.yml", e);
			}
		}
		return gitHubConfigTemplate;
	}

	/**
	 * Get the internal properties.
	 *
	 * @return internal properties
	 */
	public PropertiesWrapper getInternalProperties() {
		return internalProperties;
	}


	/**
	 * Check if it's verbose logging mode.
	 *
	 * @return true if verbose
	 */
	public boolean isVerbose() {
		return verbose;
	}

	/**
	 * Get the currently configured controller IP.
	 *
	 * @return current IP.
	 */
	public String getCurrentIP() {
		if (cluster) {
			return StringUtils.trimToEmpty(getClusterProperties().getProperty(PROP_CLUSTER_HOST));
		} else {
			return StringUtils.trimToEmpty(getControllerProperties().getProperty(PROP_CONTROLLER_IP));
		}
	}

	/**
	 * Check if the current ngrinder instance is hidden instance from the cluster.
	 *
	 * @return true if hidden.
	 */
	@SuppressWarnings("unused")
	public boolean isInvisibleRegion() {
		return getClusterProperties().getPropertyBoolean(PROP_CLUSTER_HIDDEN_REGION);
	}

	/**
	 * Check if no_more_test.lock to block further test executions exists.
	 *
	 * @return true if it exists
	 */
	public boolean hasNoMoreTestLock() {
		return exHome.exists() && exHome.getSubFile("no_more_test.lock").exists();
	}

	/**
	 * Check if shutdown.lock exists.
	 *
	 * @return true if it exists
	 */
	public boolean hasShutdownLock() {
		return exHome.exists() && exHome.getSubFile("shutdown.lock").exists();
	}

	/**
	 * Get the date of the recent announcement modification.
	 *
	 * @return the date of the recent announcement modification.
	 */
	public Date getAnnouncementDate() {
		return announcementDate;
	}

	/**
	 * Get ngrinder help URL.
	 *
	 * @return help URL
	 */
	public String getHelpUrl() {
		return getControllerProperties().getProperty(PROP_CONTROLLER_HELP_URL);
	}

	public PropertiesWrapper getClusterProperties() {
		return clusterProperties;
	}

	public PropertiesWrapper getLdapProperties() {
		return ldapProperties;
	}

	/**
	 * Get the time out milliseconds which would be used between the console and the agent while preparing to test.
	 */
	public long getInactiveClientTimeOut() {
		return getControllerProperties().getPropertyLong(PROP_CONTROLLER_INACTIVE_CLIENT_TIME_OUT);
	}

	public boolean isEnableStatistics() {
		return getControllerProperties().getPropertyBoolean(PROP_CONTROLLER_ENABLE_STATISTICS);
	}

	/**
	 * Get Csv Separator.
	 * default csvSeparator Value is comma
	 *
	 * @return String csvSeparator
	 */
	public String getCsvSeparator() {
		String csvSeparator = getControllerProperties().getProperty(PROP_CONTROLLER_CSV_SEPARATOR);
		if (("tab").equals(csvSeparator)) {
			csvSeparator = "\t";
		} else if (("semicolon").equals(csvSeparator)) {
			csvSeparator = ";";
		} else {
			csvSeparator = ",";
		}

		return csvSeparator;
	}

}
