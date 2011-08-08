/*
 * Copyright 2002-2010 the original author or authors.
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

package org.grails.plugins.elasticsearch.conversion

import org.codehaus.groovy.grails.commons.GrailsDomainClassProperty
import org.elasticsearch.common.xcontent.XContentBuilder
import static org.elasticsearch.common.xcontent.XContentFactory.*
import org.codehaus.groovy.grails.commons.GrailsDomainClass
import org.codehaus.groovy.grails.commons.ApplicationHolder
import org.grails.plugins.elasticsearch.conversion.marshall.DeepDomainClassMarshaller
import org.grails.plugins.elasticsearch.conversion.marshall.DefaultMarshallingContext
import org.grails.plugins.elasticsearch.conversion.marshall.DefaultMarshaller
import org.grails.plugins.elasticsearch.conversion.marshall.MapMarshaller
import org.grails.plugins.elasticsearch.conversion.marshall.CollectionMarshaller
import org.codehaus.groovy.grails.commons.DomainClassArtefactHandler
import java.beans.PropertyEditor
import org.grails.plugins.elasticsearch.conversion.marshall.PropertyEditorMarshaller
import org.grails.plugins.elasticsearch.conversion.marshall.Marshaller
import org.grails.plugins.elasticsearch.conversion.marshall.SearchableReferenceMarshaller
import org.codehaus.groovy.grails.orm.hibernate.cfg.GrailsHibernateUtil
import org.apache.log4j.Logger

/**
 * Marshall objects as JSON.
 */
class JSONDomainFactory {

    def elasticSearchContextHolder

    static LOG = Logger.getLogger(JSONDomainFactory.class)
    /**
     * The default marshallers, not defined by user
     */
    def static DEFAULT_MARSHALLERS = [
            (Map): MapMarshaller,
            (Collection): CollectionMarshaller
    ]

    /**
     * Create and use the correct marshaller for a peculiar class
     * @param object The instance to marshall
     * @param marshallingContext The marshalling context associate with the current marshalling process
     * @return Object The result of the marshall operation.
     */
    public delegateMarshalling(object, marshallingContext, maxDepth = 0) {
        if (object == null) {
            return null
        }
        def marshaller = null
        def objectClass = object.getClass()

        // Resolve collections.
        // Check for direct marshaller matching
        if (object instanceof Collection) {
            marshaller = new CollectionMarshaller()
        }


        if (!marshaller && DEFAULT_MARSHALLERS[objectClass]) {
            marshaller = DEFAULT_MARSHALLERS[objectClass].newInstance()
        }

        if (!marshaller) {

            // Check if we arrived from searchable domain class.
            def parentObject = marshallingContext.peekDomainObject()
            if (parentObject && marshallingContext.lastParentPropertyName && DomainClassArtefactHandler.isDomainClass(parentObject.getClass())) {
                GrailsDomainClass domainClass = getDomainClass(parentObject)
                def propertyMapping = elasticSearchContextHolder.getMappingContext(domainClass)?.getPropertyMapping(marshallingContext.lastParentPropertyName)
                def converter = propertyMapping?.converter
                // Property has converter information. Lets see how we can apply it.
                if (converter) {
                    // Property editor?
                    if (converter instanceof Class) {
                        if (PropertyEditor.isAssignableFrom(converter)) {
                            marshaller = new PropertyEditorMarshaller(propertyEditorClass:converter)
                        }
                    }
                } else if (propertyMapping?.reference) {
                    def refClass = propertyMapping.getBestGuessReferenceType()
                    LOG.trace "delegateMarshalling ($objectClass): We have a reference to: $refClass, using a SearchableReferenceMarshaller"
                    marshaller = new SearchableReferenceMarshaller(refClass:refClass)
                } else if (propertyMapping?.component) {
                    LOG.trace "delegateMarshalling ($objectClass): We have a component, using a DeepDomainClassMarshaller";
                    marshaller = new DeepDomainClassMarshaller()
                }
            }
        }

        if (!marshaller) {
            // TODO : support user custom marshaller/converter (& marshaller registration)
            // Check for domain classes
            if (DomainClassArtefactHandler.isDomainClass(objectClass)) {
                /*def domainClassName = objectClass.simpleName.substring(0,1).toLowerCase() + objectClass.simpleName.substring(1)
             SearchableClassPropertyMapping propMap = elasticSearchContextHolder.getMappingContext(domainClassName).getPropertyMapping(marshallingContext.lastParentPropertyName)*/
                def domainClass = getDomainClass(object)
                def mappingContextOfChildObject = elasticSearchContextHolder.getMappingContext(domainClass)
                if (mappingContextOfChildObject?.root) {
                    //def propertyMapping = elasticSearchContextHolder.getMappingContext(objectClass)?.getPropertyMapping(marshallingContext.lastParentPropertyName)
                    def refClass = objectClass //propertyMapping.getBestGuessReferenceType()
                    LOG.trace "delegateMarshalling ($objectClass): object is a root-mapped domain class. using SearchableReferenceMarshaller using a refClass of $refClass"
                    marshaller = new SearchableReferenceMarshaller(refClass:refClass)
                } else {
                    LOG.trace "delegateMarshalling ($objectClass): object is a non root-mapped domain class. using DeepDomainClassMarshaller."
                    marshaller = new DeepDomainClassMarshaller()
                }
            } else {
                // Check for inherited marshaller matching
                def inheritedMarshaller = DEFAULT_MARSHALLERS.find { key, value -> key.isAssignableFrom(objectClass)}
                if (inheritedMarshaller) {
                    marshaller = DEFAULT_MARSHALLERS[inheritedMarshaller.key].newInstance()
                    LOG.trace "delegateMarshalling ($objectClass): Found a marshaller from $objectClass: ${marshaller.class}"
                } else {
                    // If no marshaller was found, use the default one
                    LOG.trace "delegateMarshalling ($objectClass): Could not find a marshaller. Using a DefaultMarshaller."
                    marshaller = new DefaultMarshaller()
                }
            }
        }

        marshaller.marshallingContext = marshallingContext
        marshaller.elasticSearchContextHolder = elasticSearchContextHolder
        marshaller.maxDepth = maxDepth
        marshaller.marshall(object)
    }

    private static GrailsDomainClass getDomainClass(instance) {
        def grailsApplication = ApplicationHolder.application
        grailsApplication.domainClasses.find {it.clazz == GrailsHibernateUtil.unwrapIfProxy(instance).class}
    }

    /**
     * Build an XContentBuilder representing a domain instance in JSON.
     * Use as a source to an index request to ElasticSearch.
     * @param instance A domain class instance.
     * @return
     */
    public XContentBuilder buildJSON(instance) {
        def domainClass = getDomainClass(instance)
        def json = jsonBuilder().startObject()
        // TODO : add maxDepth in custom mapping (only for "seachable components")
        def scm = elasticSearchContextHolder.getMappingContext(domainClass)
        def marshallingContext = new DefaultMarshallingContext(maxDepth: 5, parentFactory: this)
        marshallingContext.push(instance)
        // Build the json-formated map that will contain the data to index
        scm.propertiesMapping.each { scpm ->
            marshallingContext.lastParentPropertyName = scpm.propertyName
            def res = delegateMarshalling(instance."${scpm.propertyName}", marshallingContext)
           json.field(scpm.propertyName, res)
        }
        marshallingContext.pop()
        json.endObject()
        json.close()
        json
    }
}
