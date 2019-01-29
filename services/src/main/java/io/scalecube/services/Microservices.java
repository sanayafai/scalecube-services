package io.scalecube.services;

import static java.util.stream.Collectors.toMap;

import com.codahale.metrics.MetricRegistry;
import io.scalecube.services.ServiceCall.Call;
import io.scalecube.services.discovery.ServiceScanner;
import io.scalecube.services.discovery.api.ServiceDiscovery;
import io.scalecube.services.discovery.api.ServiceDiscoveryConfig;
import io.scalecube.services.exceptions.DefaultErrorMapper;
import io.scalecube.services.exceptions.ServiceProviderErrorMapper;
import io.scalecube.services.gateway.Gateway;
import io.scalecube.services.gateway.GatewayConfig;
import io.scalecube.services.methods.ServiceMethodRegistry;
import io.scalecube.services.methods.ServiceMethodRegistryImpl;
import io.scalecube.services.metrics.Metrics;
import io.scalecube.services.registry.ServiceRegistryImpl;
import io.scalecube.services.registry.api.ServiceRegistry;
import io.scalecube.services.transport.ServiceTransportConfig;
import io.scalecube.services.transport.api.Address;
import io.scalecube.services.transport.api.ClientTransport;
import io.scalecube.services.transport.api.ServerTransport;
import io.scalecube.services.transport.api.ServiceTransport;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;

/**
 * The ScaleCube-Services module enables to provision and consuming microservices in a cluster.
 * ScaleCube-Services provides Reactive application development platform for building distributed
 * applications Using microservices and fast data on a message-driven runtime that scales
 * transparently on multi-core, multi-process and/or multi-machines Most microservices frameworks
 * focus on making it easy to build individual microservices. ScaleCube allows developers to run a
 * whole system of microservices from a single command. removing most of the boilerplate code,
 * ScaleCube-Services focuses development on the essence of the service and makes it easy to create
 * explicit and typed protocols that compose. True isolation is achieved through shared-nothing
 * design. This means the services in ScaleCube are autonomous, loosely coupled and mobile (location
 * transparent)—necessary requirements for resilance and elasticity ScaleCube services requires
 * developers only to two simple Annotations declaring a Service but not regards how you build the
 * service component itself. the Service component is simply java class that implements the service
 * Interface and ScaleCube take care for the rest of the magic. it derived and influenced by Actor
 * model and reactive and streaming patters but does not force application developers to it.
 * ScaleCube-Services is not yet-anther RPC system in the sense its is cluster aware to provide:
 *
 * <ul>
 * <li>location transparency and discovery of service instances.
 * <li>fault tolerance using gossip and failure detection.
 * <li>share nothing - fully distributed and decentralized architecture.
 * <li>Provides fluent, java 8 lambda apis.
 * <li>Embeddable and lightweight.
 * <li>utilizes completable futures but primitives and messages can be used as well completable
 * futures gives the advantage of composing and chaining service calls and service results.
 * <li>low latency
 * <li>supports routing extensible strategies when selecting service end-points
 * </ul>
 *
 * <b>basic usage example:</b>
 *
 * <pre>{@code
 * // Define a service interface and implement it:
 * &#64; Service
 * public interface GreetingService {
 *      &#64; ServiceMethod
 *      Mono<String> sayHello(String string);
 *  }
 *
 *  public class GreetingServiceImpl implements GreetingService {
 *    &#64; Override
 *    public Mono<String> sayHello(String name) {
 *      return Mono.just("hello to: " + name);
 *    }
 *  }
 *
 *  // Build a microservices cluster instance:
 *  Microservices microservices = Microservices.builder()
 *       // Introduce GreetingServiceImpl pojo as a micro-service:
 *      .services(new GreetingServiceImpl())
 *      .startAwait();
 *
 *  // Create microservice proxy to GreetingService.class interface:
 *  GreetingService service = microservices.call().create()
 *      .api(GreetingService.class);
 *
 *  // Invoke the greeting service async:
 *  service.sayHello("joe").subscribe(resp->{
 *    // handle response
 *  });
 *
 * }</pre>
 */
public class Microservices {

  private static final Logger log = LoggerFactory.getLogger(Microservices.class);

  private final MonoProcessor<Void> start = MonoProcessor.create();
  private final MonoProcessor<Void> onStart = MonoProcessor.create();
  private final MonoProcessor<Void> shutdown = MonoProcessor.create();
  private final MonoProcessor<Void> onShutdown = MonoProcessor.create();

  private String id;
  private Metrics metrics;
  private Map<String, String> tags;
  private List<ServiceInfo> serviceInfos;
  private List<ServiceProvider> serviceProviders;
  private ServiceRegistry serviceRegistry;
  private ServiceMethodRegistry methodRegistry;
  private ServiceTransportBootstrap transportBootstrap;
  private GatewayBootstrap gatewayBootstrap;
  private ServiceDiscovery discovery;
  private Consumer<ServiceDiscoveryConfig.Builder> discoveryOptions;
  private ServiceProviderErrorMapper errorMapper;

  public Microservices() {

    id = UUID.randomUUID().toString();
    tags = new HashMap<>();
    serviceInfos = new ArrayList<>();
    serviceProviders = new ArrayList<>();
    serviceRegistry = new ServiceRegistryImpl();
    methodRegistry = new ServiceMethodRegistryImpl();
    gatewayBootstrap = new GatewayBootstrap();
    discovery = ServiceDiscovery.getDiscovery();
    errorMapper = DefaultErrorMapper.INSTANCE;

    start
        .then(doStart())
        .doOnSuccess(avoid -> onStart.onComplete())
        .doOnError(onStart::onError)
        .subscribe(
            null,
            thread -> {
              log.error("{} failed to start, cause: {}", this, thread.toString());
              shutdown();
            });

    shutdown
        .then(doShutdown())
        .doFinally(s -> onShutdown.onComplete())
        .subscribe(
            null,
            thread -> log.warn("{} failed on doShutdown(): {}", this, thread.toString()),
            () -> log.debug("Shutdown {}", this));
  }

  public Microservices(Microservices msBase) {
    this.metrics = msBase.metrics;
    this.tags = new HashMap<>(msBase.tags);
    this.serviceProviders = new ArrayList<>(msBase.serviceProviders);
    this.serviceRegistry = msBase.serviceRegistry;
    this.methodRegistry = msBase.methodRegistry;
    this.transportBootstrap = msBase.transportBootstrap;
    this.gatewayBootstrap = msBase.gatewayBootstrap;
    this.discovery = msBase.discovery;
    this.discoveryOptions = msBase.discoveryOptions;
    this.errorMapper = msBase.errorMapper;
  }

  public Microservices metrics(Metrics metrics) {
    Microservices msNew = new Microservices(this);
    msNew.metrics = metrics;
    return msNew;
  }

  public Microservices metrics(MetricRegistry metrics) {
    Microservices msNew = new Microservices(this);
    msNew.metrics = new Metrics(metrics);
    return msNew;
  }

  public Microservices tags(Map<String, String> tags) {
    Microservices msNew = new Microservices(this);
    msNew.tags.putAll(tags);
    return msNew;
  }

  public Microservices services(ServiceProvider serviceProvider) {
    Microservices msNew = new Microservices(this);
    msNew.serviceProviders.add(serviceProvider);
    return msNew;
  }

  public Microservices services(Object... services) {
    Microservices msNew = new Microservices(this);
    msNew.serviceProviders.add(
        call ->
            Arrays.stream(services)
                .map(
                    s ->
                        s instanceof ServiceInfo
                            ? (ServiceInfo) s
                            : ServiceInfo.fromServiceInstance(s).build())
                .collect(Collectors.toList()));
    return msNew;
  }

  public Microservices services(ServiceInfo... services) {
    Microservices msNew = new Microservices(this);
    msNew.serviceProviders.add(call -> Arrays.stream(services).collect(Collectors.toList()));
    return msNew;
  }

  public Microservices serviceRegistry(ServiceRegistry serviceRegistry) {
    Microservices msNew = new Microservices(this);
    msNew.serviceRegistry = serviceRegistry;
    return msNew;
  }

  public Microservices methodRegistry(ServiceMethodRegistry methodRegistry) {
    Microservices msNew = new Microservices(this);
    msNew.methodRegistry = methodRegistry;
    return msNew;
  }

  public Microservices discovery(ServiceDiscovery discovery) {
    Microservices msNew = new Microservices(this);
    msNew.discovery = discovery;
    return msNew;
  }

  public Microservices discovery(Consumer<ServiceDiscoveryConfig.Builder> discoveryOptions) {
    Microservices msNew = new Microservices(this);
    msNew.discoveryOptions = discoveryOptions;
    return msNew;
  }

  public Microservices transport(Consumer<ServiceTransportConfig.Builder> transportOptions) {
    Microservices msNew = new Microservices(this);
    msNew.transportBootstrap =
        new ServiceTransportBootstrap(
            ServiceTransportConfig.builder(transportOptions).build());
    return msNew;
  }

  public Microservices gateway(GatewayConfig config) {
    Microservices msNew = new Microservices(this);
    msNew.gatewayBootstrap.addConfig(config);
    return msNew;
  }

  public Microservices errorMapper(ServiceProviderErrorMapper errorMapper) {
    Microservices msNew = new Microservices(this);
    msNew.errorMapper = errorMapper;
    return msNew;
  }

  public Microservices startAwait() {
    return start().block();
  }

  public Mono<Microservices> start() {
    return Mono.defer(
        () -> {
          start.onComplete();
          return onStart.thenReturn(this);
        });
  }

  public Mono<Microservices> doStart() {
    return Mono.defer(() -> new Microservices(this).transportBootstrap
        .start(methodRegistry)
        .flatMap(
            input -> {
              ClientTransport clientTransport = transportBootstrap.clientTransport();
              InetSocketAddress serviceAddress = transportBootstrap.serviceAddress();

              Call call = new Call(clientTransport, methodRegistry, serviceRegistry);

              // invoke service providers and register services
              serviceProviders
                  .stream()
                  .flatMap(serviceProvider -> serviceProvider.provide(call).stream())
                  .forEach(this::collectAndRegister);

              // register services in service registry
              ServiceEndpoint endpoint = null;
              if (!serviceInfos.isEmpty()) {
                String serviceHost = serviceAddress.getHostString();
                int servicePort = serviceAddress.getPort();
                endpoint = ServiceScanner.scan(serviceInfos, id, serviceHost, servicePort, tags);
                serviceRegistry.registerService(endpoint);
              }

              // configure discovery and publish to the cluster
              ServiceDiscoveryConfig discoveryConfig =
                  ServiceDiscoveryConfig.builder(discoveryOptions)
                      .serviceRegistry(serviceRegistry)
                      .endpoint(endpoint)
                      .build();
              return discovery
                  .start(discoveryConfig)
                  .then(Mono.defer(this::doInjection))
                  .then(Mono.defer(() -> startGateway(call)))
                  .thenReturn(this);
            })
        .onErrorResume(
            ex -> {
              // return original error then shutdown
              return Mono.when(Mono.error(ex), doShutdown()).cast(Microservices.class);
            }));
  }

  private Mono<GatewayBootstrap> startGateway(Call call) {
    return gatewayBootstrap.start(transportBootstrap.workerPool(), call, metrics);
  }

  private Mono<Microservices> doInjection() {
    List<Object> serviceInstances =
        serviceInfos.stream().map(ServiceInfo::serviceInstance).collect(Collectors.toList());
    return Mono.just(Reflect.inject(this, serviceInstances));
  }

  private void collectAndRegister(ServiceInfo serviceInfo) {
    // collect
    serviceInfos.add(serviceInfo);

    // register service
    methodRegistry.registerService(
        serviceInfo.serviceInstance(),
        Optional.ofNullable(serviceInfo.errorMapper()).orElse(errorMapper));
  }

  public InetSocketAddress serviceAddress() {
    return transportBootstrap.serviceAddress();
  }

  public Call call() {
    return new Call(transportBootstrap.clientTransport(), methodRegistry, serviceRegistry);
  }

  public InetSocketAddress gatewayAddress(String name, Class<? extends Gateway> gatewayClass) {
    return gatewayBootstrap.gatewayAddress(name, gatewayClass);
  }

  public Map<GatewayConfig, InetSocketAddress> gatewayAddresses() {
    return gatewayBootstrap.gatewayAddresses();
  }

  public ServiceDiscovery discovery() {
    return this.discovery;
  }

  public void shutdown() {
    shutdown.onComplete();
  }

  /**
   * Shutdown instance and clear resources.
   *
   * @return result of shutdown
   */
  public Mono<Void> doShutdown() {
    return Mono.defer(
        () ->
            Mono.whenDelayError(
                Optional.ofNullable(discovery).map(ServiceDiscovery::shutdown).orElse(Mono.empty()),
                Optional.ofNullable(gatewayBootstrap)
                    .map(GatewayBootstrap::shutdown)
                    .orElse(Mono.empty()),
                Optional.ofNullable(transportBootstrap)
                    .map(ServiceTransportBootstrap::shutdown)
                    .orElse(Mono.empty())));
  }

  private static class GatewayBootstrap {

    private Set<GatewayConfig> gatewayConfigs = new HashSet<>(); // config
    private Map<GatewayConfig, Gateway> gatewayInstances = new HashMap<>(); // calculated

    private GatewayBootstrap addConfig(GatewayConfig config) {
      if (!gatewayConfigs.add(config)) {
        throw new IllegalArgumentException(
            "GatewayConfig with name: '"
                + config.name()
                + "' and gatewayClass: '"
                + config.gatewayClass().getName()
                + "' was already defined");
      }
      return this;
    }

    private Mono<GatewayBootstrap> start(Executor workerPool, Call call, Metrics metrics) {
      return Flux.fromIterable(gatewayConfigs)
          .flatMap(
              gatewayConfig ->
                  Gateway.getGateway(gatewayConfig.gatewayClass())
                      .start(gatewayConfig, workerPool, call, metrics)
                      .doOnSuccess(gw -> gatewayInstances.put(gatewayConfig, gw)))
          .then(Mono.just(this));
    }

    private Mono<Void> shutdown() {
      return Mono.defer(
          () ->
              gatewayInstances != null && !gatewayInstances.isEmpty()
                  ? Mono.when(
                  gatewayInstances.values().stream().map(Gateway::stop).toArray(Mono[]::new))
                  : Mono.empty());
    }

    private InetSocketAddress gatewayAddress(String name, Class<? extends Gateway> gatewayClass) {
      Optional<GatewayConfig> result =
          gatewayInstances
              .keySet()
              .stream()
              .filter(config -> config.name().equals(name))
              .filter(config -> config.gatewayClass() == gatewayClass)
              .findFirst();

      if (!result.isPresent()) {
        throw new IllegalArgumentException(
            "Didn't find gateway address under name: '"
                + name
                + "' and gateway class: '"
                + gatewayClass.getName()
                + "'");
      }

      return gatewayInstances.get(result.get()).address();
    }

    private Map<GatewayConfig, InetSocketAddress> gatewayAddresses() {
      return Collections.unmodifiableMap(
          gatewayInstances
              .entrySet()
              .stream()
              .collect(toMap(Entry::getKey, e -> e.getValue().address())));
    }
  }

  private static class ServiceTransportBootstrap {

    private static final int DEFAULT_NUM_OF_THREADS = Runtime.getRuntime().availableProcessors();

    private String serviceHost; // config
    private int servicePort; // config
    private ServiceTransport transport; // config or calculated
    private ClientTransport clientTransport; // calculated
    private ServerTransport serverTransport; // calculated
    private ServiceTransport.Resources transportResources; // calculated
    private InetSocketAddress serviceAddress; // calculated
    private int numOfThreads; // calculated

    public ServiceTransportBootstrap(ServiceTransportConfig options) {
      this.serviceHost = options.host();
      this.servicePort = Optional.ofNullable(options.port()).orElse(0);
      this.numOfThreads =
          Optional.ofNullable(options.numOfThreads()).orElse(DEFAULT_NUM_OF_THREADS);
      this.transport = options.transport();
    }

    private ServiceTransport transport() {
      return transport;
    }

    private ClientTransport clientTransport() {
      return clientTransport;
    }

    private Executor workerPool() {
      return transportResources.workerPool().orElse(null);
    }

    private InetSocketAddress serviceAddress() {
      return serviceAddress;
    }

    private Mono<ServiceTransportBootstrap> start(ServiceMethodRegistry methodRegistry) {
      return Mono.defer(
          () -> {
            this.transport =
                Optional.ofNullable(this.transport).orElseGet(ServiceTransport::getTransport);

            this.transportResources = transport.resources(numOfThreads);
            this.clientTransport = transport.clientTransport(transportResources);
            this.serverTransport = transport.serverTransport(transportResources);

            // bind service serverTransport transport
            return serverTransport
                .bind(servicePort, methodRegistry)
                .map(
                    listenAddress -> {
                      // prepare service host:port for exposing
                      int port = listenAddress.getPort();
                      String host =
                          Optional.ofNullable(serviceHost)
                              .orElseGet(() -> Address.getLocalIpAddress().getHostAddress());
                      this.serviceAddress = InetSocketAddress.createUnresolved(host, port);
                      return this;
                    });
          });
    }

    private Mono<Void> shutdown() {
      return Mono.defer(
          () ->
              Mono.when(
                  Optional.ofNullable(serverTransport)
                      .map(ServerTransport::stop)
                      .orElse(Mono.empty()),
                  transportResources.shutdown()));
    }
  }
}
