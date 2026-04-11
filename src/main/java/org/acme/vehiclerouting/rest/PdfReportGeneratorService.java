package org.acme.vehiclerouting.rest;

import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Font;
import org.openpdf.text.Paragraph;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import jakarta.enterprise.context.ApplicationScoped;
import org.acme.vehiclerouting.domain.Vehicle;
import org.acme.vehiclerouting.domain.VehicleRoutePlan;
import org.acme.vehiclerouting.domain.Visit;
import org.acme.vehiclerouting.domain.VisitType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;

@ApplicationScoped
public class PdfReportGeneratorService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PdfReportGeneratorService.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    public byte[] generateRoutePlanReport(VehicleRoutePlan routePlan) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document document = new Document();
            PdfWriter.getInstance(document, baos);
            document.open();

            Font titleFont = new Font(Font.HELVETICA, 18, Font.BOLD);
            document.add(new Paragraph("Relatório de Roteirização - " + routePlan.getName(), titleFont));
            document.add(new Paragraph("Score Final: " + routePlan.getScore()));
            document.add(new Paragraph(" ")); // Espaço

            for (Vehicle vehicle : routePlan.getVehicles()) {
                if (vehicle.getVisits() == null || vehicle.getVisits().isEmpty()) {
                    continue;
                }

                document.add(new Paragraph("Veículo: " + vehicle.getId() + " | Capacidade Total: " + vehicle.getCapacity()));

                PdfPTable table = new PdfPTable(5);
                table.setWidthPercentage(100);
                table.setSpacingBefore(10f);
                table.setSpacingAfter(20f);

                table.addCell("Passageiro");
                table.addCell("Ação");
                table.addCell("Janela (Min-Max)");
                table.addCell("Chegada Prevista");
                table.addCell("Ocupação da Van");

                int currentLoad = 0;

                for (Visit visit : vehicle.getVisits()) {
                    if (visit.getVisitType() == VisitType.PICKUP) {
                        currentLoad++;
                    } else if (visit.getVisitType() == VisitType.DELIVERY) {
                        currentLoad--;
                    }

                    String timeWindow = visit.getMinStartTime().format(TIME_FORMATTER) + " - " + visit.getMaxEndTime().format(TIME_FORMATTER);
                    String arrivalTime = visit.getArrivalTime() != null ? visit.getArrivalTime().format(TIME_FORMATTER) : "N/A";
                    String action = visit.getVisitType() == VisitType.PICKUP ? "Embarque" : "Desembarque";

                    table.addCell(visit.getPassenger().getName());
                    table.addCell(action);
                    table.addCell(timeWindow);
                    table.addCell(arrivalTime);
                    table.addCell(currentLoad + " / " + vehicle.getCapacity());
                }

                document.add(table);
            }

            document.close();
            return baos.toByteArray();

        } catch (DocumentException | java.io.IOException e) {
            LOGGER.error("Erro fatal ao gerar o PDF", e);
            throw new RuntimeException("Falha na geração do PDF", e);
        }
    }
}