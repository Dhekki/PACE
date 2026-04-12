let autoRefreshIntervalId = null;
let initialized = false;
let optimizing = false;
let demoDataId = null;
let scheduleId = null;
let loadedRoutePlan = null;
let newVisit = null;
let visitMarker = null;
const solveButton = $('#solveButton');
const stopSolvingButton = $('#stopSolvingButton');
const vehiclesTable = $('#vehicles');
const analyzeButton = $('#analyzeButton');
const downloadPdfButton = $('#downloadPdfButton');

/*************************************** Map constants and variable definitions  **************************************/

const homeLocationMarkerByIdMap = new Map();
const visitMarkerByIdMap = new Map();

const map = L.map('map', {doubleClickZoom: false}).setView([-12.26, -38.96], 13);
const visitGroup = L.layerGroup().addTo(map);
const homeLocationGroup = L.layerGroup().addTo(map);
const routeGroup = L.layerGroup().addTo(map);

/************************************ Time line constants and variable definitions ************************************/

if (typeof vis !== 'undefined' && vis.moment) {
    vis.moment.defineLocale('pt-br', {
        months : 'Janeiro_Fevereiro_Março_Abril_Maio_Junho_Julho_Agosto_Setembro_Outubro_Novembro_Dezembro'.split('_'),
        monthsShort : 'Jan_Fev_Mar_Abr_Mai_Jun_Jul_Ago_Set_Out_Nov_Dez'.split('_'),
        weekdays : 'Domingo_Segunda-feira_Terça-feira_Quarta-feira_Quinta-feira_Sexta-feira_Sábado'.split('_'),
        weekdaysShort : 'Dom_Seg_Ter_Qua_Qui_Sex_Sáb'.split('_'),
        weekdaysMin : 'Do_2ª_3ª_4ª_5ª_6ª_Sá'.split('_')
    });
    vis.moment.locale('pt-br');
}

const byVehiclePanel = document.getElementById("byVehiclePanel");
const byVehicleTimelineOptions = {
    timeAxis: {scale: "hour"},
    orientation: {axis: "top"},
    xss: {disabled: true}, // Items are XSS safe through JQuery
    stack: false,
    stackSubgroups: false,
    zoomMin: 1000 * 60 * 60, // A single hour in milliseconds
    zoomMax: 1000 * 60 * 60 * 24, // A single day in milliseconds
    locale: 'pt-br'
};
const byVehicleGroupData = new vis.DataSet();
const byVehicleItemData = new vis.DataSet();
const byVehicleTimeline = new vis.Timeline(byVehiclePanel, byVehicleItemData, byVehicleGroupData, byVehicleTimelineOptions);

const byVisitPanel = document.getElementById("byVisitPanel");
const byVisitTimelineOptions = {
    timeAxis: {scale: "hour"},
    orientation: {axis: "top"},
    verticalScroll: true,
    xss: {disabled: true}, // Items are XSS safe through JQuery
    stack: false,
    stackSubgroups: false,
    zoomMin: 1000 * 60 * 60, // A single hour in milliseconds
    zoomMax: 1000 * 60 * 60 * 24, // A single day in milliseconds
    locale: 'pt-br'
};
const byVisitGroupData = new vis.DataSet();
const byVisitItemData = new vis.DataSet();
const byVisitTimeline = new vis.Timeline(byVisitPanel, byVisitItemData, byVisitGroupData, byVisitTimelineOptions);

const BG_COLORS = ["#009E73","#0072B2","#D55E00","#000000","#CC79A7","#E69F00","#F0E442","#F6768E","#C10020","#A6BDD7","#803E75","#007D34","#56B4E9","#999999","#8DD3C7","#FFD92F","#B3DE69","#FB8072","#80B1D3","#B15928","#CAB2D6","#1B9E77","#E7298A","#6A3D9A"];
const FG_COLORS = ["#FFFFFF","#FFFFFF","#FFFFFF","#FFFFFF","#FFFFFF","#000000","#000000","#FFFFFF","#FFFFFF","#000000","#FFFFFF","#FFFFFF","#FFFFFF","#000000","#000000","#000000","#000000","#FFFFFF","#000000","#FFFFFF","#000000","#FFFFFF","#FFFFFF","#FFFFFF"];
let COLOR_MAP = new Map()
let nextColorIndex = 0

function pickColor(object) {
    let color = COLOR_MAP.get(object);
    if (color !== undefined) {
        return color;
    }
    let index = nextColorIndex++;
    color = {bg : BG_COLORS[index], fg: FG_COLORS[index]};
    COLOR_MAP.set(object,color);
    return color;
}

/************************************ Initialize ************************************/

$(document).ready(function () {
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        maxZoom: 19,
        attribution: '&copy; <a href="https://www.openstreetmap.org/">OpenStreetMap</a> contributors',
    }).addTo(map);

    solveButton.click(solve);
    stopSolvingButton.click(stopSolving);
    analyzeButton.click(analyze);
    downloadPdfButton.click(downloadPdfReport);
    refreshSolvingButtons(false);

    $("#byVehicleTab").on('shown.bs.tab', function (event) {
        byVehicleTimeline.redraw();
    })
    $("#byVisitTab").on('shown.bs.tab', function (event) {
        byVisitTimeline.redraw();
    })
    map.on('click', function (e) {
        visitMarker = L.circleMarker(e.latlng);
        visitMarker.setStyle({color: 'green'});
        visitMarker.addTo(map);
        openRecommendationModal(e.latlng.lat, e.latlng.lng);
    });
    $("#newVisitModal").on("hidden.bs.modal", function () {
        map.removeLayer(visitMarker);
    });

    $('#csvUpload').on('change', function(e) {
        const file = e.target.files[0];
        if (!file) return;

        const reader = new FileReader();
        reader.onload = function(e) {
            const text = e.target.result;
            parseAndLoadCsv(text);
        };
        reader.readAsText(file);

        this.value = null;
    });

    setupAjax();
    fetchDemoData();
});

function colorByVehicle(vehicle) {
    return vehicle === null ? null : pickColor('vehicle' + vehicle.id);
}

function formatDrivingTime(drivingTimeInSeconds) {
    return `${Math.floor(drivingTimeInSeconds / 3600)}h ${Math.round((drivingTimeInSeconds % 3600) / 60)}m`;
}

function homeLocationPopupContent(vehicle) {
    return `<h5>Veículo ${vehicle.id}</h5>
Garagem / Ponto de Partida`;
}

function visitPopupContent(visit) {
    const arrival = visit.arrivalTime ? `<h6>Arrival at ${showTimeOnly(visit.arrivalTime)}.</h6>` : '';
    return `<h5>${visit.name}</h5>
    <h6>Demand: ${visit.demand}</h6>
    <h6>Available from ${showTimeOnly(visit.minStartTime)} to ${showTimeOnly(visit.maxEndTime)}.</h6>
    ${arrival}`;
}

function showTimeOnly(localDateTimeString) {
    return JSJoda.LocalDateTime.parse(localDateTimeString).toLocalTime();
}

function getHomeLocationMarker(vehicle) {
    let marker = homeLocationMarkerByIdMap.get(vehicle.id);
    if (marker) {
        return marker;
    }
    const color = colorByVehicle(vehicle);
    const homeIcon = L.divIcon({
        html: `<i class="fas fa-shuttle-van" style="color: ${color.bg}; font-size: 24px; text-shadow: -1px -1px 0 #000, 1px -1px 0 #000, -1px 1px 0 #000, 1px 1px 0 #000;"></i>`,
        className: 'home-location-icon',
        iconSize: [24, 24],
        iconAnchor: [12, 12]
    });
    marker = L.marker(vehicle.homeLocation, { icon: homeIcon });
    marker.addTo(homeLocationGroup).bindPopup();
    homeLocationMarkerByIdMap.set(vehicle.id, marker);
    return marker;
}

function getVisitMarker(visit) {
    let marker = visitMarkerByIdMap.get(visit.id);
    if (marker) {
        return marker;
    }

    let bgColor = '#999999';
    if (visit.vehicle != null) {
        bgColor = pickColor('vehicle' + visit.vehicle).bg;
    }

    let isPickup = visit.name && visit.name.includes('PICKUP');

    let iconClass = isPickup ? 'fa-street-view' : 'fa-flag-checkered';

    const visitIcon = L.divIcon({
        html: `
            <div style="
                background-color: ${bgColor}; 
                border: 2px solid white; 
                border-radius: 50%; 
                width: 24px; 
                height: 24px; 
                display: flex; 
                align-items: center; 
                justify-content: center;
                box-shadow: 0 0 4px rgba(0,0,0,0.5);">
                <i class="fas ${iconClass}" style="color: white; font-size: 12px;"></i>
            </div>
        `,
        className: 'visit-custom-icon',
        iconSize: [24, 24],
        iconAnchor: [12, 12]
    });

    marker = L.marker(visit.location, { icon: visitIcon });
    marker.addTo(visitGroup).bindPopup();
    visitMarkerByIdMap.set(visit.id, marker);
    return marker;
}

function renderRoutes(solution) {
    if (!initialized) {
        const bounds = [solution.southWestCorner, solution.northEastCorner];
        map.fitBounds(bounds);
    }

    vehiclesTable.children().remove();
    solution.vehicles.forEach(function (vehicle) {
        getHomeLocationMarker(vehicle).setPopupContent(homeLocationPopupContent(vehicle));

        const {id, capacity, maxLoad, totalDrivingTimeSeconds} = vehicle;
        const percentage = maxLoad / capacity * 100;
        const color = colorByVehicle(vehicle);

        vehiclesTable.append(`
      <tr>
        <td>
          <i class="fas fa-shuttle-van" id="home-${id}"
            style="color: ${color.bg}; font-size: 1.2rem; display: inline-block; width: 1rem; text-align: center">
          </i>
        </td>
        <td>Veículo ${id}</td>
        <td>
          <div class="progress" data-bs-toggle="tooltip-load" data-bs-placement="left" data-html="true"
            title="Pico Máximo: ${maxLoad} passageiros / Capacidade: ${capacity}">
            <div class="progress-bar" role="progressbar" style="width: ${percentage}%">${maxLoad}/${capacity}</div>
          </div>
        </td>
        <td>${formatDrivingTime(totalDrivingTimeSeconds)}</td>
      </tr>`);
    });

    solution.visits.forEach(function (visit) {
        if (visitMarkerByIdMap.has(visit.id)) {
            visitGroup.removeLayer(visitMarkerByIdMap.get(visit.id));
            visitMarkerByIdMap.delete(visit.id);
        }

        const marker = getVisitMarker(visit);

        let popTitle = visit.name.includes('PICKUP') ? "Embarque" : "Desembarque";
        let passengerName = visit.name.split(' (')[0];

        const arrival = visit.arrivalTime ? `<b>Chegada Prevista:</b> ${showTimeOnly(visit.arrivalTime)}<br>` : '';
        const window = `<b>Janela de Tempo:</b> ${showTimeOnly(visit.minStartTime)} até ${showTimeOnly(visit.maxEndTime)}`;

        let popupHtml = `
            <div style="font-family: sans-serif; min-width: 150px;">
                <h5 style="margin-bottom: 5px; color: #333;">${popTitle}</h5>
                <h6 style="margin-top: 0; color: #666; border-bottom: 1px solid #ccc; padding-bottom: 5px;">${passengerName}</h6>
                ${arrival}
                ${window}
            </div>
        `;

        marker.setPopupContent(popupHtml);
    });

    routeGroup.clearLayers();
    const visitByIdMap = new Map(solution.visits.map(visit => [visit.id, visit]));

    for (let vehicle of solution.vehicles) {
        if (!vehicle.visits || vehicle.visits.length === 0) continue;

        const color = colorByVehicle(vehicle).bg;
        const homeLocation = vehicle.homeLocation;
        const locations = vehicle.visits.map(visitId => visitByIdMap.get(visitId).location);
        const routePoints = [homeLocation, ...locations, homeLocation];

        const pointParams = routePoints.map(loc => `point=${loc[0]},${loc[1]}`).join('&');
        const ghUrl = `http://localhost:8989/route?${pointParams}&profile=car&points_encoded=false`;

        $.getJSON(ghUrl, function(data) {
            if (data && data.paths && data.paths.length > 0) {
                const coordinates = data.paths[0].points.coordinates.map(coord => [coord[1], coord[0]]);
                L.polyline(coordinates, {color: color, weight: 5, opacity: 0.8}).addTo(routeGroup);
            }
        }).fail(function() {
            console.warn(`Falha ao buscar geometria da Van ${vehicle.id}. Usando linha reta.`);
            L.polyline(routePoints, {color: color, dashArray: '5, 10'}).addTo(routeGroup);
        });
    }

    $('#score').text(solution.score);
    let totalPassengers = solution.visits.length / 2;
    $("#info").text(`Este cenário possui ${totalPassengers} passageiros aguardando distribuição entre ${solution.vehicles.length} veículos.`);
    $('#drivingTime').text(formatDrivingTime(solution.totalDrivingTimeSeconds));
}

function renderTimelines(routePlan) {
    byVehicleGroupData.clear();
    byVisitGroupData.clear();
    byVehicleItemData.clear();
    byVisitItemData.clear();

    $.each(routePlan.vehicles, function (index, vehicle) {
        const totalTransported = vehicle.visits ? Math.floor(vehicle.visits.length / 2) : 0;

        const vehicleWithLoad = `<h5 class="card-title mb-1">Veículo ${vehicle.id}</h5>
                                 <p class="mb-0 text-muted" style="font-size: 0.85rem; line-height: 1.2;">Passageiros transportados: <b>${totalTransported}</b></p>`;

        byVehicleGroupData.add({id: vehicle.id, content: vehicleWithLoad});
    });

    $.each(routePlan.visits, function (index, visit) {
        const minStartTime = JSJoda.LocalDateTime.parse(visit.minStartTime);
        const maxEndTime = JSJoda.LocalDateTime.parse(visit.maxEndTime);
        const serviceDuration = JSJoda.Duration.ofSeconds(visit.serviceDuration);

        let translatedName = visit.name.replace("(PICKUP)", "(Embarque)").replace("(DELIVERY)", "(Desembarque)");

        const visitGroupElement = $(`<div/>`)
            .append($(`<h5 class="card-title mb-1"/>`).text(translatedName));
        byVisitGroupData.add({
            id: visit.id,
            content: visitGroupElement.html()
        });

        byVisitItemData.add({
            id: visit.id + "_readyToDue",
            group: visit.id,
            start: visit.minStartTime,
            end: visit.maxEndTime,
            type: "background",
            style: "background-color: #8AE23433"
        });

        if (visit.vehicle == null || !visit.arrivalTime || !visit.startServiceTime || !visit.departureTime) {
            const byJobJobElement = $(`<div/>`)
                .append($(`<h5 class="card-title mb-1"/>`).text(`Não Atribuído`));

            byVisitItemData.add({
                id: visit.id + '_unassigned',
                group: visit.id,
                content: byJobJobElement.html(),
                start: minStartTime.toString(),
                type: 'box',
                align: 'left',
                style: "background-color: #EF292999"
            });
        } else {
            const arrivalTime = JSJoda.LocalDateTime.parse(visit.arrivalTime);
            const beforeReady = arrivalTime.isBefore(minStartTime);
            const arrivalPlusService = arrivalTime.plus(serviceDuration);
            const afterDue = arrivalPlusService.isAfter(maxEndTime);

            const byVehicleElement = $(`<div/>`)
                .append('<div/>')
                .append($(`<h5 class="card-title mb-1"/>`).text(translatedName));

            const byVisitElement = $(`<div/>`)
                .append($(`<h5 class="card-title mb-1"/>`).text('Veículo ' + visit.vehicle));

            const byVehicleTravelElement = $(`<div/>`)
                .append($(`<h5 class="card-title mb-1"/>`).text('Em Trânsito'));

            const previousDeparture = arrivalTime.minusSeconds(visit.drivingTimeSecondsFromPreviousStandstill);
            byVehicleItemData.add({
                id: visit.id + '_travel',
                group: visit.vehicle,
                subgroup: visit.vehicle,
                content: byVehicleTravelElement.html(),
                start: previousDeparture.toString(),
                end: visit.arrivalTime,
                style: "background-color: #f7dd8f90"
            });

            if (beforeReady) {
                const byVehicleWaitElement = $(`<div/>`)
                    .append($(`<h5 class="card-title mb-1"/>`).text('Espera'));

                byVehicleItemData.add({
                    id: visit.id + '_wait',
                    group: visit.vehicle,
                    subgroup: visit.vehicle,
                    content: byVehicleWaitElement.html(),
                    start: visit.arrivalTime,
                    end: visit.minStartTime
                });
            }

            let serviceElementBackground = afterDue ? '#EF292999' : '#83C15955'

            byVehicleItemData.add({
                id: visit.id + '_service',
                group: visit.vehicle,
                subgroup: visit.vehicle,
                content: byVehicleElement.html(),
                start: visit.startServiceTime,
                end: visit.departureTime,
                style: "background-color: " + serviceElementBackground
            });

            byVisitItemData.add({
                id: visit.id,
                group: visit.id,
                content: byVisitElement.html(),
                start: visit.startServiceTime,
                type: 'box',
                align: 'left',
                style: "background-color: " + serviceElementBackground
            });
        }
    });

    $.each(routePlan.vehicles, function (index, vehicle) {
        if (vehicle.visits && vehicle.visits.length > 0) {
            let lastVisit = routePlan.visits.filter((visit) => visit.id === vehicle.visits[vehicle.visits.length -1]).pop();
            if (lastVisit && lastVisit.departureTime) {
                byVehicleItemData.add({
                    id: vehicle.id + '_travelBackToHomeLocation',
                    group: vehicle.id,
                    subgroup: vehicle.id,
                    content: $(`<div/>`).append($(`<h5 class="card-title mb-1"/>`).text('Retorno')).html(),
                    start: lastVisit.departureTime,
                    end: vehicle.arrivalTime,
                    style: "background-color: #f7dd8f90"
                });
            }
        }
    });

    if (!initialized) {
        byVehicleTimeline.setWindow(routePlan.startDateTime, routePlan.endDateTime);
        byVisitTimeline.setWindow(routePlan.startDateTime, routePlan.endDateTime);
    }
}

function analyze() {
    analyzeScore(loadedRoutePlan, "/route-plans/analyze")
}

function openRecommendationModal(lat, lng) {

    if (!('score' in loadedRoutePlan) || optimizing) {
        map.removeLayer(visitMarker);
        visitMarker = null;
        let message = "Please click the Solve button before adding new visits.";
        if (optimizing) {
            message = "Please wait for the solving process to finish."
        }
        alert(message);
        return;
    }
    const visitId = Math.max(...loadedRoutePlan.visits.map(c => parseInt(c.id))) + 1;
    newVisit = {id: visitId, location: [lat, lng]};
    addNewVisit(visitId, lat, lng, map, visitMarker);
}

function getRecommendationsModal() {
    let formValid = true;
    formValid = validateFormField(newVisit, 'name' , '#inputName') && formValid;
    formValid = validateFormField(newVisit, 'demand' , '#inputDemand') && formValid;
    formValid = validateFormField(newVisit, 'minStartTime' , '#inputMinStartTime') && formValid;
    formValid = validateFormField(newVisit, 'maxEndTime' , '#inputMaxStartTime') && formValid;
    formValid = validateFormField(newVisit, 'serviceDuration' , '#inputDuration') && formValid;
    if (formValid) {
        const updatedMinStartTime = JSJoda.LocalDateTime.parse(newVisit['minStartTime'], JSJoda.DateTimeFormatter.ofPattern('yyyy-M-d HH:mm')).format(JSJoda.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        const updatedMaxEndTime = JSJoda.LocalDateTime.parse(newVisit['maxEndTime'], JSJoda.DateTimeFormatter.ofPattern('yyyy-M-d HH:mm')).format(JSJoda.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        const updatedVisit = {...newVisit, serviceDuration: `PT${newVisit['serviceDuration']}M`, minStartTime: updatedMinStartTime, maxEndTime: updatedMaxEndTime};
        let updatedVisitList = [...loadedRoutePlan['visits']];
        updatedVisitList.push(updatedVisit);
        let updatedSolution = {...loadedRoutePlan, visits: updatedVisitList};
        requestRecommendations(updatedVisit.id, updatedSolution, "/route-plans/recommendation")
    }
}

function validateFormField(target, fieldName, inputName) {
    target[fieldName] = $(inputName).val();
    if ($(inputName).val() == "") {
        $(inputName).addClass("is-invalid");
    } else {
        $(inputName).removeClass("is-invalid");
    }
    return $(inputName).val() != "";
}

function applyRecommendationModal(recommendations) {
    let checkedRecommendation = null;
    recommendations.forEach((recommendation, index) => {
        if ($('#option'+ index).is(":checked")) {
            checkedRecommendation = recommendations[index];
        }
    });
    const updatedMinStartTime = JSJoda.LocalDateTime.parse(newVisit['minStartTime'], JSJoda.DateTimeFormatter.ofPattern('yyyy-M-d HH:mm')).format(JSJoda.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    const updatedMaxEndTime = JSJoda.LocalDateTime.parse(newVisit['maxEndTime'], JSJoda.DateTimeFormatter.ofPattern('yyyy-M-d HH:mm')).format(JSJoda.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    const updatedVisit = {...newVisit, serviceDuration: `PT${newVisit['serviceDuration']}M`, minStartTime: updatedMinStartTime, maxEndTime: updatedMaxEndTime};
    let updatedVisitList = [...loadedRoutePlan['visits']];
    updatedVisitList.push(updatedVisit);
    let updatedSolution = {...loadedRoutePlan, visits: updatedVisitList};
    // see recommended-fit.js
    applyRecommendation(updatedSolution, newVisit.id, checkedRecommendation.proposition.vehicleId, checkedRecommendation.proposition.index,
        "/route-plans/recommendation/apply");
}

function updateSolutionWithNewVisit(newSolution) {
    loadedRoutePlan = newSolution;
    renderRoutes(newSolution);
    renderTimelines(newSolution);
    $('#newVisitModal').modal('hide');
}

// TODO: move the general functionality to the webjar.

function setupAjax() {
    $.ajaxSetup({
        headers: {
            'Content-Type': 'application/json',
            'Accept': 'application/json,text/plain', // plain text is required by solve() returning UUID of the solver job
        }
    });

    // Extend jQuery to support $.put() and $.delete()
    jQuery.each(["put", "delete"], function (i, method) {
        jQuery[method] = function (url, data, callback, type) {
            if (jQuery.isFunction(data)) {
                type = type || callback;
                callback = data;
                data = undefined;
            }
            return jQuery.ajax({
                url: url,
                type: method,
                dataType: type,
                data: data,
                success: callback
            });
        };
    });
}

function solve() {
    let planToSend = JSON.parse(JSON.stringify(loadedRoutePlan));

    planToSend.visits.forEach(visit => {
        delete visit.name;
        delete visit.location;
    });

    $.post("/route-plans", JSON.stringify(planToSend), function (data) {
        scheduleId = data;
        refreshSolvingButtons(true);
    }).fail(function (xhr, ajaxOptions, thrownError) {
        showError("Start solving failed.", xhr);
        refreshSolvingButtons(false);
    }, "text");
}

function refreshSolvingButtons(solving) {
    optimizing = solving;

    if (solving) {
        $("#solveButton").hide();
        $("#visitButton").hide();
        $("#stopSolvingButton").show();
        $("#downloadPdfButton").hide();

        if (autoRefreshIntervalId == null) {
            autoRefreshIntervalId = setInterval(refreshRoutePlan, 2000);
        }
    } else {
        $("#solveButton").show();
        $("#visitButton").show();
        $("#stopSolvingButton").hide();

        if (scheduleId) {
            $("#downloadPdfButton").show();
        }

        if (autoRefreshIntervalId != null) {
            clearInterval(autoRefreshIntervalId);
            autoRefreshIntervalId = null;
        }
    }
}

function refreshRoutePlan() {
    let path = "/route-plans/" + scheduleId;
    if (scheduleId === null) {
        if (demoDataId === null) {
            alert("Please select a test data set.");
            return;
        }

        path = "/demo-data/" + demoDataId;
    }

    $.getJSON(path, function (routePlan) {
        loadedRoutePlan = routePlan;
        refreshSolvingButtons(routePlan.solverStatus != null && routePlan.solverStatus !== "NOT_SOLVING");
        renderRoutes(routePlan);
        renderTimelines(routePlan);
        initialized = true;
    }).fail(function (xhr, ajaxOptions, thrownError) {
        showError("Getting route plan has failed.", xhr);
        refreshSolvingButtons(false);
    });
}

function stopSolving() {
    $.delete("/route-plans/" + scheduleId, function () {
        refreshSolvingButtons(false);
        refreshRoutePlan();
    }).fail(function (xhr, ajaxOptions, thrownError) {
        showError("Stop solving failed.", xhr);
    });
}

function fetchDemoData() {
    $.get("/demo-data", function (data) {
        data.forEach(function (item) {
            $("#testDataButton").append($('<a id="' + item + 'TestData" class="dropdown-item" href="#">' + item + '</a>'));

            $("#" + item + "TestData").click(function () {
                switchDataDropDownItemActive(item);
                scheduleId = null;
                demoDataId = item;
                initialized = false;
                homeLocationGroup.clearLayers();
                homeLocationMarkerByIdMap.clear();
                visitGroup.clearLayers();
                visitMarkerByIdMap.clear();
                refreshRoutePlan();
            });
        });

        demoDataId = data[0];
        switchDataDropDownItemActive(demoDataId);

        refreshRoutePlan();
    }).fail(function (xhr, ajaxOptions, thrownError) {
        // disable this page as there is no data
        $("#demo").empty();
        $("#demo").html("<h1><p style=\"justify-content: center\">No test data available</p></h1>")
    });
}

function switchDataDropDownItemActive(newItem) {
    activeCssClass = "active";
    $("#testDataButton > a." + activeCssClass).removeClass(activeCssClass);
    $("#" + newItem + "TestData").addClass(activeCssClass);
}

function copyTextToClipboard(id) {
    var text = $("#" + id).text().trim();

    var dummy = document.createElement("textarea");
    document.body.appendChild(dummy);
    dummy.value = text;
    dummy.select();
    document.execCommand("copy");
    document.body.removeChild(dummy);
}

function showError(title, xhr) {
    let serverErrorMessage = !xhr.responseJSON ? `${xhr.status}: ${xhr.statusText}` : xhr.responseJSON.message;
    let serverErrorCode = !xhr.responseJSON ? `unknown` : xhr.responseJSON.code;
    let serverErrorId = !xhr.responseJSON ? `----` : xhr.responseJSON.id;
    let serverErrorDetails = !xhr.responseJSON ? `no details provided` : xhr.responseJSON.details;

    if (xhr.responseJSON && !serverErrorMessage) {
        serverErrorMessage = JSON.stringify(xhr.responseJSON);
        serverErrorCode = xhr.statusText + '(' + xhr.status + ')';
        serverErrorId = `----`;
    }

    console.error(title + "\n" + serverErrorMessage + " : " + serverErrorDetails);
    const notification = $(`<div class="toast" role="alert" aria-live="assertive" aria-atomic="true" style="min-width: 50rem"/>`)
        .append($(`<div class="toast-header bg-danger">
                 <strong class="me-auto text-dark">Error</strong>
                 <button type="button" class="btn-close" data-bs-dismiss="toast" aria-label="Close"></button>
               </div>`))
        .append($(`<div class="toast-body"/>`)
            .append($(`<p/>`).text(title))
            .append($(`<pre/>`)
                .append($(`<code/>`).text(serverErrorMessage + "\n\nCode: " + serverErrorCode + "\nError id: " + serverErrorId))
            )
        );
    $("#notificationPanel").append(notification);
    notification.toast({delay: 30000});
    notification.toast('show');
}

function downloadPdfReport() {
    if (!scheduleId) return;

    fetch(`/route-plans/${scheduleId}/report`)
        .then(response => {
            if (!response.ok) throw new Error("Erro ao gerar PDF.");
            return response.blob();
        })
        .then(blob => {
            const url = window.URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = `Rota_${scheduleId}.pdf`;
            document.body.appendChild(a);
            a.click();
            window.URL.revokeObjectURL(url);
            document.body.removeChild(a);
        })
        .catch(error => showError("Falha no Download do PDF", { statusText: error.message }));
}

function parseAndLoadCsv(csvText) {
    const lines = csvText.split('\n');

    let parsedVehicles = [];
    let parsedPassengers = [];
    let parsedVisits = [];

    const tomorrow = new Date();
    tomorrow.setDate(tomorrow.getDate() + 1);
    const dateStr = tomorrow.toISOString().split('T')[0];

    for (let i = 1; i < lines.length; i++) {
        let line = lines[i].trim();
        if (!line) continue;

        let cols = line.split(/[,;]/).map(c => c.trim());
        if (cols.length < 4) continue;

        let id = cols[0];
        let capOrDemand = parseInt(cols[1]);
        let latOrig = parseFloat(cols[2]);
        let lonOrig = parseFloat(cols[3]);

        if (id.toLowerCase().startsWith('van-')) {
            parsedVehicles.push({
                id: id.replace(/van-/i, ''),
                capacity: capOrDemand,
                homeLocation: [latOrig, lonOrig],
                departureTime: `${dateStr}T07:00:00`,
                visits: [],
                maxLoad: 0,
                totalDrivingTimeSeconds: 0
            });
        } else {
            let latDest = parseFloat(cols[4]);
            let lonDest = parseFloat(cols[5]);

            let horaInicio = (cols[6] || "08:00").padStart(5, '0');
            let horaFim = (cols[7] || "10:00").padStart(5, '0');
            let duracaoMinutos = cols[8] || "10";

            let duracaoSegundos = parseInt(duracaoMinutos) * 60;

            let passengerObj = {
                id: "p_" + id.replace(/\s+/g, '_').toLowerCase(),
                name: id,
                pickupLocation: [latOrig, lonOrig],
                dropoffLocation: [latDest, lonDest],
                demand: capOrDemand
            };
            parsedPassengers.push(passengerObj);

            parsedVisits.push({
                id: passengerObj.id + "_P",
                name: passengerObj.name + " (PICKUP)",
                passenger: passengerObj.id,
                visitType: "PICKUP",
                location: [latOrig, lonOrig],
                minStartTime: `${dateStr}T${horaInicio}:00`,
                maxEndTime: `${dateStr}T${horaFim}:00`,
                serviceDuration: duracaoSegundos
            });

            parsedVisits.push({
                id: passengerObj.id + "_D",
                name: passengerObj.name + " (DELIVERY)",
                passenger: passengerObj.id,
                visitType: "DELIVERY",
                location: [latDest, lonDest],
                minStartTime: `${dateStr}T${horaInicio}:00`,
                maxEndTime: `${dateStr}T${horaFim}:00`,
                serviceDuration: duracaoSegundos
            });
        }
    }

    if (parsedVehicles.length === 0 || parsedPassengers.length === 0) {
        alert("Erro: O CSV precisa ter pelo menos 1 Van e 1 Passageiro.");
        return;
    }

    loadedRoutePlan = {
        name: "Upload Customizado CSV",
        vehicles: parsedVehicles,
        passengers: parsedPassengers,
        visits: parsedVisits,
        southWestCorner: [-12.30, -39.00],
        northEastCorner: [-12.15, -38.90],
        startDateTime: `${dateStr}T00:00:00`,
        endDateTime: `${dateStr}T23:59:59`,
        solverStatus: "NOT_SOLVING",
        totalDrivingTimeSeconds: 0
    };

    homeLocationGroup.clearLayers();
    homeLocationMarkerByIdMap.clear();
    visitGroup.clearLayers();
    visitMarkerByIdMap.clear();
    routeGroup.clearLayers();
    scheduleId = null;
    initialized = false;

    renderRoutes(loadedRoutePlan);
    renderTimelines(loadedRoutePlan);

    console.log("CSV Carregado com Sucesso! Passageiros:", parsedPassengers.length, "Vans:", parsedVehicles.length);
}
