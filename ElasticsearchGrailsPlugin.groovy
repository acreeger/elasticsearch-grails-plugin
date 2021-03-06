/*
 * Copyright 2002-2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.grails.plugins.elasticsearch.ElasticSearchHelper
import org.grails.plugins.elasticsearch.conversion.CustomEditorRegistar
import org.grails.plugins.elasticsearch.ElasticSearchContextHolder
import org.grails.plugins.elasticsearch.ClientNodeFactoryBean
import org.codehaus.groovy.grails.orm.hibernate.HibernateEventListeners
import org.grails.plugins.elasticsearch.AuditEventListener
import org.grails.plugins.elasticsearch.conversion.JSONDomainFactory
import org.springframework.context.ApplicationContext
import org.codehaus.groovy.grails.commons.GrailsApplication
import grails.util.GrailsUtil
import org.grails.plugins.elasticsearch.util.DomainDynamicMethodsUtils
import org.grails.plugins.elasticsearch.mapping.SearchableClassMappingConfigurator
import org.grails.plugins.elasticsearch.conversion.unmarshall.DomainClassUnmarshaller
import org.apache.log4j.Logger
import org.grails.plugins.elasticsearch.index.IndexRequestQueue

class ElasticsearchGrailsPlugin {


    static LOG = Logger.getLogger("org.grails.plugins.elasticsearch.ElasticsearchGrailsPlugin")

    // the plugin version
    def version = "0.17.4.1-SNAPSHOT"
    // the version or versions of Grails the plugin is designed for
    def grailsVersion = "1.3.6 > *"
    // the other plugins this plugin depends on
    def dependsOn = [
            domainClass: "1.0 > *",
            hibernate: "1.0 > *"
    ]
    def loadAfter = ['services']
    // resources that are excluded from plugin packaging
    def pluginExcludes = [
            "grails-app/views/error.gsp",
            "grails-app/controllers/test/ElasticSearchController.groovy",
            "grails-app/services/test/TestCaseService.groovy",
            "grails-app/views/elasticSearch/index.gsp",
            "grails-app/domain/test/*"
    ]

    // TODO Fill in these fields
    def author = "Manuarii Stein"
    def authorEmail = "mstein@doc4web.com"
    def title = "ElasticSearch Plugin"
    def description = '''\\
Integrates ElasticSearch with Grails, allowing to index domain instances or raw data.
Based on Graeme Rocher spike.
'''

    // URL to the plugin's documentation
    def documentation = "http://grails.org/plugin/elasticsearch"

    def doWithWebDescriptor = { xml ->
        // TODO Implement additions to web.xml (optional), this event occurs before
    }

    def doWithSpring = {
        def esConfig = getConfiguration(parentCtx, application)

        elasticSearchHelper(ElasticSearchHelper) {
            elasticSearchClient = ref("elasticSearchClient")
        }
        elasticSearchContextHolder(ElasticSearchContextHolder) {
            config = esConfig
        }
        elasticSearchClient(ClientNodeFactoryBean) { bean ->
            elasticSearchContextHolder = ref("elasticSearchContextHolder")
            bean.destroyMethod = 'shutdown'
        }
        indexRequestQueue(IndexRequestQueue) {
            elasticSearchContextHolder = ref("elasticSearchContextHolder")
            elasticSearchClient = ref("elasticSearchClient")
            jsonDomainFactory = ref("jsonDomainFactory")
            sessionFactory = ref("sessionFactory")
        }
        searchableClassMappingConfigurator(SearchableClassMappingConfigurator) { bean ->
            elasticSearchContext = ref("elasticSearchContextHolder")
            grailsApplication = ref("grailsApplication")
            elasticSearchClient = ref("elasticSearchClient")
            config = esConfig

            bean.initMethod = 'configureAndInstallMappings'
        }
        domainInstancesRebuilder(DomainClassUnmarshaller) {
            elasticSearchContextHolder = ref("elasticSearchContextHolder")
            elasticSearchClient = ref("elasticSearchClient")
            grailsApplication = ref("grailsApplication")
        }
        customEditorRegistrar(CustomEditorRegistar)
        jsonDomainFactory(JSONDomainFactory) {
            elasticSearchContextHolder = ref("elasticSearchContextHolder")
        }
        auditListener(AuditEventListener) {
            elasticSearchContextHolder = ref("elasticSearchContextHolder")
        }

        if (!esConfig.disableAutoIndex) {
            // do not install audit listener if auto-indexing is disabled.
            hibernateEventListeners(HibernateEventListeners) {
                listenerMap = [
                        'post-delete': auditListener,
                        'post-collection-update': auditListener,
//                        'save-update': auditListener,
                        'post-update': auditListener,
                        'post-insert': auditListener,
                        'flush': auditListener
                ]
            }
        }
    }

    def onShutdown = { event ->
        event.ctx.getBean("elasticSearchClient").close()
    }

    def doWithDynamicMethods = { ctx ->
        // Define the custom ElasticSearch mapping for searchable domain classes
        DomainDynamicMethodsUtils.injectDynamicMethods(application.domainClasses, application, ctx)
    }

    def doWithApplicationContext = { applicationContext ->
        // Implement post initialization spring config (optional)
        def esConfig = getConfiguration(parentCtx, application)
        if (esConfig.bulkIndexOnStartup) {
            LOG.debug "Performing bulk index."
            applicationContext.elasticSearchService.index()
        }
    }

    def onChange = { event ->
        // TODO Implement code that is executed when any artefact that this plugin is
        // watching is modified and reloaded. The event contains: event.source,
        // event.application, event.manager, event.ctx, and event.plugin.
    }

    def onConfigChange = { event ->
        // TODO Implement code that is executed when the project configuration changes.
        // The event is the same as for 'onChange'.
    }

    // Get a configuration instance

    private getConfiguration(ApplicationContext applicationContext, GrailsApplication application) {
        def config = application.config
        // try to load it from class file and merge into GrailsApplication#config
        // Config.groovy properties override the default one
        try {
            Class dataSourceClass = application.getClassLoader().loadClass("DefaultElasticSearch")
            ConfigSlurper configSlurper = new ConfigSlurper(GrailsUtil.getEnvironment())
            Map binding = new HashMap()
            binding.userHome = System.properties['user.home']
            binding.grailsEnv = application.metadata["grails.env"]
            binding.appName = application.metadata["app.name"]
            binding.appVersion = application.metadata["app.version"]
            configSlurper.binding = binding
            def defaultConfig = configSlurper.parse(dataSourceClass)
            config = defaultConfig.merge(config)
            return config.elasticSearch
        } catch (ClassNotFoundException e) {
            LOG.debug("Not found: ${e.message}")
        }
        // try to get it from GrailsApplication#config
        if (config.containsKey("elasticSearch")) {
            if (!config.elasticSearch.date?.formats) {
                config.elasticSearch.date.formats = ["yyyy-MM-dd'T'HH:mm:ss'Z'"]
            }
            return config.elasticSearch
        }

        // No config found, add some default and obligatory properties
        ConfigSlurper configSlurper = new ConfigSlurper(GrailsUtil.getEnvironment())
        config.merge(configSlurper.parse({
            elasticSeatch {
                date.formats = ["yyyy-MM-dd'T'HH:mm:ss'Z'"]
            }
        }))

        return config
    }
}