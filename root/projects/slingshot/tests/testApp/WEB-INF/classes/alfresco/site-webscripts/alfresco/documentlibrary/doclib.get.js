model.jsonModel = {
   services: [
      {
         name: "alfresco/services/LoggingService",
         config: {
            loggingPreferences: {
               enabled: true,
               all: true
            }
         }
      },
      "alfresco/services/DocumentService",
      "alfresco/services/ErrorReporter"
   ],
   widgets:[
      {
         name: "alfresco/documentlibrary/AlfDocumentList",
         config: {
            nodeRef: "alfresco://company/home",
            path: "/",
            widgets: [
               {
                  name: "alfresco/documentlibrary/views/AlfDocumentListView",
                  config: {
                     widgets: [
                        {
                           name: "alfresco/documentlibrary/views/layouts/Row",
                           config: {
                              widgets: [
                                 {
                                    name: "alfresco/documentlibrary/views/layouts/Cell",
                                    config: {
                                       width: "20px",
                                       widgets: [
                                          {
                                             name: "alfresco/renderers/FileType",
                                             config: {
                                                size: "small",
                                                renderAsLink: true,
                                                linkClickTopic: "ALF_DOCLIST_NAV"
                                             }
                                          }
                                       ]
                                    }
                                 },
                                 {
                                    name: "alfresco/documentlibrary/views/layouts/Cell",
                                    config: {
                                       widgets: [
                                          {
                                             name: "alfresco/renderers/Property",
                                             config: {
                                                propertyToRender: "node.properties.cm:name",
                                                renderAsLink: true,
                                                linkClickTopic: "ALF_DOCLIST_NAV"
                                             }
                                          }
                                       ]
                                    }
                                 }
                              ]
                           }
                        }
                     ]
                  }
               }
            ]
         }
      }
   ]
};