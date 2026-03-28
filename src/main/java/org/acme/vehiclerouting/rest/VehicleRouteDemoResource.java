package org.acme.vehiclerouting.rest;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.PrimitiveIterator;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;

import org.acme.vehiclerouting.domain.Location;
import org.acme.vehiclerouting.domain.Passenger;
import org.acme.vehiclerouting.domain.Vehicle;
import org.acme.vehiclerouting.domain.VehicleRoutePlan;
import org.acme.vehiclerouting.domain.Visit;
import org.acme.vehiclerouting.domain.VisitType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Tag(name = "Demo data", description = "Timefold-provided demo vehicle routing data.")
@Path("demo-data")
public class VehicleRouteDemoResource {

    private static final String[] FIRST_NAMES = { "Amy", "Beth", "Carl", "Dan", "Elsa", "Flo", "Gus", "Hugo", "Ivy", "Jay" };
    private static final String[] LAST_NAMES = { "Cole", "Fox", "Green", "Jones", "King", "Li", "Poe", "Rye", "Smith", "Watt" };
    private static final int[] SERVICE_DURATION_MINUTES = { 10, 20, 30, 40 };
    private static final LocalTime MORNING_WINDOW_START = LocalTime.of(8, 0);
    private static final LocalTime MORNING_WINDOW_END = LocalTime.of(12, 0);
    private static final LocalTime AFTERNOON_WINDOW_START = LocalTime.of(13, 0);
    private static final LocalTime AFTERNOON_WINDOW_END = LocalTime.of(18, 0);

    public enum DemoData {
        FEIRA_DE_SANTANA(0, 50, 6, LocalTime.of(7, 30),
                1, 2, 15, 30,
                new Location(-12.3000, -39.0000), // SouthWest Corner
                new Location(-12.2200, -38.9200)), // NorthEast Corner
        PHILADELPHIA(1, 55, 6, LocalTime.of(7, 30),
                1, 2, 15, 30,
                new Location(39.7656099067391, -76.83782328143754),
                new Location(40.77636644354855, -74.9300739430771)),
        HARTFORT(2, 50, 6, LocalTime.of(7, 30),
                1, 3, 20, 30,
                new Location(41.48366520850297, -73.15901689943055),
                new Location(41.99512052869307, -72.25114548877427)),
        FIRENZE(3, 77, 6, LocalTime.of(7, 30),
                1, 2, 20, 40,
                new Location(43.751466, 11.177210), new Location(43.809291, 11.290195));

        private long seed;
        private int visitCount;
        private int vehicleCount;
        private LocalTime vehicleStartTime;
        private int minDemand;
        private int maxDemand;
        private int minVehicleCapacity;
        private int maxVehicleCapacity;
        private Location southWestCorner;
        private Location northEastCorner;

        DemoData(long seed, int visitCount, int vehicleCount, LocalTime vehicleStartTime,
                 int minDemand, int maxDemand, int minVehicleCapacity, int maxVehicleCapacity,
                 Location southWestCorner, Location northEastCorner) {
            this.seed = seed;
            this.visitCount = visitCount;
            this.vehicleCount = vehicleCount;
            this.vehicleStartTime = vehicleStartTime;
            this.minDemand = minDemand;
            this.maxDemand = maxDemand;
            this.minVehicleCapacity = minVehicleCapacity;
            this.maxVehicleCapacity = maxVehicleCapacity;
            this.southWestCorner = southWestCorner;
            this.northEastCorner = northEastCorner;
        }
    }

    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "List of demo data represented as IDs.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = DemoData.class, type = SchemaType.ARRAY))) })
    @Operation(summary = "List demo data.")
    @GET
    public DemoData[] list() {
        return DemoData.values();
    }

    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "Unsolved demo route plan.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = VehicleRoutePlan.class))) })
    @Operation(summary = "Find an unsolved demo route plan by ID.")
    @GET
    @Path("/{demoDataId}")
    public VehicleRoutePlan generate(@Parameter(description = "Unique identifier of the demo data.",
            required = true) @PathParam("demoDataId") DemoData demoData) {
        return build(demoData);
    }

    public VehicleRoutePlan build(DemoData demoData) {
        String name = "demo";

        Random random = new Random(demoData.seed);
        PrimitiveIterator.OfDouble latitudes = random
                .doubles(demoData.southWestCorner.getLatitude(), demoData.northEastCorner.getLatitude()).iterator();
        PrimitiveIterator.OfDouble longitudes = random
                .doubles(demoData.southWestCorner.getLongitude(), demoData.northEastCorner.getLongitude()).iterator();

        PrimitiveIterator.OfInt demand = random.ints(demoData.minDemand, demoData.maxDemand + 1)
                .iterator();
        PrimitiveIterator.OfInt vehicleCapacity = random.ints(demoData.minVehicleCapacity, demoData.maxVehicleCapacity + 1)
                .iterator();

        AtomicLong vehicleSequence = new AtomicLong();
        Supplier<Vehicle> vehicleSupplier = () -> new Vehicle(
                String.valueOf(vehicleSequence.incrementAndGet()),
                vehicleCapacity.nextInt(),
                new Location(latitudes.nextDouble(), longitudes.nextDouble()),
                tomorrowAt(demoData.vehicleStartTime));

        List<Vehicle> vehicles = Stream.generate(vehicleSupplier)
                .limit(demoData.vehicleCount)
                .collect(Collectors.toList());

        Supplier<String> nameSupplier = () -> {
            Function<String[], String> randomStringSelector = strings -> strings[random.nextInt(strings.length)];
            String firstName = randomStringSelector.apply(FIRST_NAMES);
            String lastName = randomStringSelector.apply(LAST_NAMES);
            return firstName + " " + lastName;
        };

        AtomicLong passengerSequence = new AtomicLong();
        Supplier<Passenger> passengerSupplier = () -> {
            return new Passenger(
                    String.valueOf(passengerSequence.incrementAndGet()),
                    nameSupplier.get(),
                    new Location(latitudes.nextDouble(), longitudes.nextDouble()),
                    new Location(latitudes.nextDouble(), longitudes.nextDouble()),
                    demand.nextInt());
        };

        int passengerCount = Math.max(1, demoData.visitCount / 2);
        List<Passenger> passengers = Stream.generate(passengerSupplier)
                .limit(passengerCount)
                .collect(Collectors.toList());

        AtomicLong visitSequence = new AtomicLong();
        List<Visit> visits = new ArrayList<>();

        for (Passenger passenger : passengers) {
            boolean morningTimeWindow = random.nextBoolean();

            LocalDateTime minStartTime =
                    morningTimeWindow ? tomorrowAt(MORNING_WINDOW_START) : tomorrowAt(AFTERNOON_WINDOW_START);
            LocalDateTime maxEndTime = morningTimeWindow ? tomorrowAt(MORNING_WINDOW_END) : tomorrowAt(AFTERNOON_WINDOW_END);
            int serviceDurationMinutes = SERVICE_DURATION_MINUTES[random.nextInt(SERVICE_DURATION_MINUTES.length)];

            visits.add(new Visit(
                    String.valueOf(visitSequence.incrementAndGet()),
                    passenger,
                    VisitType.PICKUP,
                    minStartTime,
                    maxEndTime,
                    Duration.ofMinutes(serviceDurationMinutes)));

            visits.add(new Visit(
                    String.valueOf(visitSequence.incrementAndGet()),
                    passenger,
                    VisitType.DELIVERY,
                    minStartTime,
                    maxEndTime,
                    Duration.ofMinutes(serviceDurationMinutes)));
        }

        return new VehicleRoutePlan(name, demoData.southWestCorner, demoData.northEastCorner,
                tomorrowAt(demoData.vehicleStartTime), tomorrowAt(LocalTime.MIDNIGHT).plusDays(1L),
                vehicles, passengers, visits);
    }

    private static LocalDateTime tomorrowAt(LocalTime time) {
        return LocalDateTime.of(LocalDate.now().plusDays(1L), time);
    }
}
