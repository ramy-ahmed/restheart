/*-
 * ========================LICENSE_START=================================
 * restheart-mongodb
 * %%
 * Copyright (C) 2014 - 2020 SoftInstigate
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * =========================LICENSE_END==================================
 */
package org.restheart.mongodb;

import static io.undertow.Handlers.path;
import static io.undertow.Handlers.pathTemplate;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.PathTemplateHandler;
import io.undertow.util.PathMatcher;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import static org.fusesource.jansi.Ansi.Color.GREEN;
import static org.fusesource.jansi.Ansi.ansi;
import org.restheart.ConfigurationException;
import org.restheart.exchange.MongoRequest;
import org.restheart.exchange.MongoResponse;
import org.restheart.handlers.PipelinedHandler;
import org.restheart.handlers.PipelinedWrappingHandler;
import static org.restheart.mongodb.MongoServiceConfigurationKeys.MONGO_MOUNT_WHAT_KEY;
import static org.restheart.mongodb.MongoServiceConfigurationKeys.MONGO_MOUNT_WHERE_KEY;
import org.restheart.mongodb.db.MongoClientSingleton;
import org.restheart.mongodb.exchange.BsonRequestContentInjector;
import org.restheart.mongodb.exchange.BsonRequestPropsInjector;
import org.restheart.mongodb.handlers.CORSHandler;
import org.restheart.mongodb.handlers.ErrorHandler;
import org.restheart.mongodb.handlers.OptionsHandler;
import org.restheart.mongodb.handlers.RequestDispatcherHandler;
import org.restheart.mongodb.handlers.injectors.AccountInjector;
import org.restheart.mongodb.handlers.injectors.ClientSessionInjector;
import org.restheart.mongodb.handlers.injectors.ETagPolicyInjector;
import org.restheart.mongodb.handlers.metrics.MetricsInstrumentationHandler;
import org.restheart.mongodb.utils.URLUtils;
import org.restheart.plugins.InjectPluginsRegistry;
import org.restheart.plugins.PluginsRegistry;
import org.restheart.plugins.RegisterPlugin;
import org.restheart.plugins.Service;
import org.restheart.utils.PluginUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Service that handles requests to mongodb resources
 *
 * @author Andrea Di Cesare <andrea@softinstigate.com>
 */
@RegisterPlugin(name = "mongo",
        description = "handles requests to mongodb resources",
        enabledByDefault = true,
        defaultURI = "/",
        priority = Integer.MIN_VALUE)
public class MongoService implements Service<MongoRequest, MongoResponse> {
    private static final Logger LOGGER = LoggerFactory
            .getLogger(MongoService.class);

    private String myURI = null;

    private PipelinedHandler pipeline;

    @InjectPluginsRegistry
    public void init(PluginsRegistry registry) {
        this.myURI = myURI();
        this.pipeline = getBasePipeline(registry);

        // init mongoMounts
        getMongoMounts().stream().forEachOrdered(mm
                -> mongoMounts.addPrefixPath(mm.uri, mm));
    }

    @Override
    public void handle(MongoRequest request, MongoResponse response) throws Exception {
        if (MongoClientSingleton.isInitialized()) {
            this.pipeline.handleRequest(request.getExchange());
        } else {
            final var error = "Service mongo is not initialized. "
                    + "Make sure that mongoInitializer is enabled "
                    + "and executed successfully";

            response.setInError(500, error);
            LOGGER.error(error);
        }
    }

    /**
     * getHandlersPipe
     *
     * @return a GracefulShutdownHandler
     */
    private PipelinedHandler getBasePipeline(PluginsRegistry registry)
            throws ConfigurationException {
        var rootHandler = path();

        var _pipeline = PipelinedWrappingHandler.wrap(
                new ErrorHandler(
                        PipelinedHandler.pipe(
                                new MetricsInstrumentationHandler(),
                                new CORSHandler(),
                                new OptionsHandler(),
                                new AccountInjector(),
                                ClientSessionInjector.build(),
                                new ETagPolicyInjector(),
                                RequestDispatcherHandler.getInstance())));

        // check that all mounts are either all paths or all path templates
        boolean allPathTemplates = MongoServiceConfiguration.get().getMongoMounts()
                .stream()
                .map(m -> (String) m.get(MONGO_MOUNT_WHERE_KEY))
                .allMatch(url -> isPathTemplate(url));

        boolean allPaths = MongoServiceConfiguration.get().getMongoMounts()
                .stream()
                .map(m -> (String) m.get(MONGO_MOUNT_WHERE_KEY))
                .allMatch(url -> !isPathTemplate(url));

        PathTemplateHandler pathsTemplates = pathTemplate(false);

        if (!allPathTemplates && !allPaths) {
            LOGGER.error("No mongo resource mounted! Check your mongo-mounts."
                    + " where url must be either all absolute paths"
                    + " or all path templates");
        } else {
            MongoServiceConfiguration.get().getMongoMounts().stream().forEach(m -> {
                var uri = resolveURI((String) m.get(MONGO_MOUNT_WHERE_KEY));
                var resource = (String) m.get(MONGO_MOUNT_WHAT_KEY);

                if (allPathTemplates) {
                    pathsTemplates.add(uri, _pipeline);
                } else {
                    rootHandler.addPrefixPath(uri, _pipeline);
                }

                LOGGER.info(ansi().fg(GREEN)
                        .a("URI {} bound to MongoDB resource {}")
                        .reset().toString(), uri, resource);
            });

            if (allPathTemplates) {
                rootHandler.addPrefixPath(myURI(), pathsTemplates);
            }
        }

        return PipelinedWrappingHandler.wrap(rootHandler);
    }

    private static boolean isPathTemplate(final String url) {
        return (url == null)
                ? false
                : url.contains("{") && url.contains("}");
    }

    private final MongoMount DEFAULT_MONGO_MOUNT = new MongoMount("*", "/");

    /**
     * Return the MongoRequest initializer
     *
     * @return
     */
    @Override
    public Consumer<HttpServerExchange> requestInitializer() {
        return e -> {
            var path = e.getRequestPath();

            var mmm = mongoMounts.match(path);

            if (mmm != null && mmm.getValue() != null) {
                var mm = mmm.getValue();

                MongoRequest.init(e,
                        mm.uri,
                        mm.resource);
            } else {
                MongoRequest.init(e,
                        DEFAULT_MONGO_MOUNT.uri,
                        DEFAULT_MONGO_MOUNT.resource);
            }
        };
    }

    @Override
    public Consumer<HttpServerExchange> responseInitializer() {
        return e -> {
            MongoResponse.init(e);

            // BsonRequestPropsInjector and BsonRequestContentInjector requires 
            // that both MongoRequest and MongoResponse are initialized
            BsonRequestPropsInjector.inject(e);
            BsonRequestContentInjector.inject(e);
        };
    }

    @Override
    public Function<HttpServerExchange, MongoRequest> request() {
        return e -> MongoRequest.of(e);
    }

    @Override
    public Function<HttpServerExchange, MongoResponse> response() {
        return e -> MongoResponse.of(e);
    }

    /**
     * PathMatcher is used by the root PathHandler to route the call. Here we
     * use the same logic to identify the correct MongoMount in order to
     * correctly init the BsonRequest
     */
    private final PathMatcher<MongoMount> mongoMounts = new PathMatcher<>();

    @SuppressWarnings("unchecked")
    private Set<MongoMount> getMongoMounts() {
        final var ret = new LinkedHashSet<MongoMount>();

        MongoServiceConfiguration.get().getMongoMounts()
                .stream()
                .forEachOrdered(e -> {
                    ret.add(new MongoMount(
                            (String) e.get(MONGO_MOUNT_WHAT_KEY),
                            resolveURI((String) e.get(MONGO_MOUNT_WHERE_KEY))));
                });

        return ret;
    }

    /**
     *
     * @param uri
     * @return the URI composed of serviceUri + uri
     */
    private String resolveURI(String uri) {
        if ("".equals(myURI) || "/".equals(myURI)) {
            return uri;
        } else if (uri == null) {
            return URLUtils.removeTrailingSlashes(myURI);
        } else {
            return URLUtils.removeTrailingSlashes(myURI.concat(uri));
        }
    }

    private String myURI() {
        Object uri;

        if (MongoServiceConfiguration.get().getPluginsArgs() != null) {
            var myconf = MongoServiceConfiguration.get().getPluginsArgs().get("mongo");

            if (myconf != null
                    && myconf.containsKey("uri")
                    && !"/".equals(myconf.get("uri"))) {
                uri = myconf.get("uri");
            } else {
                uri = PluginUtils.defaultURI(this);
            }
        } else {
            uri = PluginUtils.defaultURI(this);
        }

        if (uri instanceof String) {
            var _uri = (String) uri;
            // make sure myURI does not end with /
            _uri = URLUtils.removeTrailingSlashes(_uri);

            return _uri;
        } else {
            throw new IllegalArgumentException("Wrong 'uri' configuration of "
                    + "mongo service.");
        }
    }

    /**
     * helper class to store mongo mounts info
     */
    private static class MongoMount {
        public String resource;
        public String uri;

        public MongoMount(String resource, String uri) {
            if (uri == null) {
                throw new IllegalArgumentException("'where' cannot be null. check your 'mongo-mounts'.");
            }

            if (!uri.startsWith("/")) {
                throw new IllegalArgumentException("'where' must start with \"/\". check your 'mongo-mounts'");
            }

            if (resource == null) {
                throw new IllegalArgumentException("'what' cannot be null. check your 'mongo-mounts'.");
            }

            if (!uri.startsWith("/") && !uri.equals("*")) {
                throw new IllegalArgumentException("'what' must be * (all db resorces) or start with \"/\". (eg. /db/coll) check your 'mongo-mounts'");
            }

            this.resource = resource;
            this.uri = org.restheart.utils.URLUtils.removeTrailingSlashes(uri);
        }

        @Override
        public String toString() {
            return "MongoMount(" + uri + " -> " + resource + ")";
        }
    }
}
