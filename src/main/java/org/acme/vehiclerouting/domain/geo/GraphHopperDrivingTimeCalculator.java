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

    private static final String GRAPH_HOPPER_URL_TEMPLATE = "http://localhost:8989/route?point=%f,%f&point=%f,%f&profile=car";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    // NOVO: Proteção contra DDoS. Máximo de 20 requisições simultâneas ao Docker.
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
            // Segura a thread se já tiver 20 requisições rolando no Docker
            concurrentRequestsLimiter.acquire();

            String url = String.format(Locale.US, GRAPH_HOPPER_URL_TEMPLATE,
                    from.getLatitude(), from.getLongitude(),
                    to.getLatitude(), to.getLongitude());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode rootNode = objectMapper.readTree(response.body());
                long timeInMillis = rootNode.path("paths").get(0).path("time").asLong();
                return timeInMillis / 1000L;
            } else {
                // Algumas coordenadas aleatórias podem cair em áreas isoladas sem ruas.
                // O GraphHopper retorna 400 Bad Request avisando que "Não achou o ponto".
                LOGGER.warn("Rota indisponível no mapa (Status: {}). Acionando Fallback pontual.", response.statusCode());
            }
        } catch (Exception e) {
            // Agora sabemos exatamente o que quebrou se falhar de novo
            LOGGER.warn("Exceção real na chamada HTTP: {}", e.getMessage());
        } finally {
            // Libera a vaga para a próxima requisição na fila
            concurrentRequestsLimiter.release();
        }

        // Retorna a linha reta apenas para o trecho problemático
        return HaversineDrivingTimeCalculator.getInstance().calculateDrivingTime(from, to);
    }
}
