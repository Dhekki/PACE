package org.acme.vehiclerouting.domain.geo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;

import org.acme.vehiclerouting.domain.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class GraphHopperDrivingTimeCalculator implements DrivingTimeCalculator {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphHopperDrivingTimeCalculator.class);
    private static final GraphHopperDrivingTimeCalculator INSTANCE = new GraphHopperDrivingTimeCalculator();

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Semaphore concurrentRequestsLimiter;

    private GraphHopperDrivingTimeCalculator() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
        this.concurrentRequestsLimiter = new Semaphore(20);
    }

    public static GraphHopperDrivingTimeCalculator getInstance() {
        return INSTANCE;
    }

    @Override
    public long calculateDrivingTime(Location from, Location to) {
        return fetchRouteTime(from, to);
    }

    @Override
    public void initDrivingTimeMaps(Collection<Location> locations) {
        List<Location> uniqueLocations = locations.stream().distinct().toList();

        if (uniqueLocations.isEmpty()) {
            return;
        }

        int totalCalls = uniqueLocations.size() * uniqueLocations.size();
        LOGGER.info("Calculando matriz O(1) com {} pontos geográficos (Total: {} requisições limitadas a 20 por vez)...", uniqueLocations.size(), totalCalls);

        uniqueLocations.parallelStream().forEach(from -> {
            Map<Location, Long> drivingTimeSeconds = new HashMap<>();

            uniqueLocations.forEach(to -> {
                if (from.equals(to)) {
                    drivingTimeSeconds.put(to, 0L);
                } else {
                    drivingTimeSeconds.put(to, fetchRouteTime(from, to));
                }
            });

            from.setDrivingTimeSeconds(drivingTimeSeconds);
        });

        LOGGER.info("Cache O(1) de Feira de Santana populado com sucesso!");
    }

    private long fetchRouteTime(Location from, Location to) {
        try {
            concurrentRequestsLimiter.acquire();

            String queryParams = String.format(Locale.US, "point=%f,%f&point=%f,%f&profile=car",
                    from.getLatitude(), from.getLongitude(),
                    to.getLatitude(), to.getLongitude());

            URI uri = new URI("http", null, "localhost", 8989, "/route", queryParams, null);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode rootNode = objectMapper.readTree(response.body());
                JsonNode paths = rootNode.path("paths");

                if (paths.isArray() && !paths.isEmpty()) {
                    long timeInMillis = paths.get(0).path("time").asLong();
                    return timeInMillis / 1000L;
                } else {
                    LOGGER.warn("GH retornou 200 OK mas não achou rota (paths vazio). Fallback ativado.");
                }
            } else {
                LOGGER.warn("Rota indisponível no mapa (Status: {}). URL tentada: {}", response.statusCode(), uri);
            }
        } catch (Exception e) {
            LOGGER.error("Exceção real na chamada HTTP para: {} -> {}", from, to, e);
        } finally {
            concurrentRequestsLimiter.release();
        }

        return HaversineDrivingTimeCalculator.getInstance().calculateDrivingTime(from, to);
    }
}
