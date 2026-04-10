const constraintTranslations = {
    "vehicleCapacity": "Capacidade do Veículo",
    "serviceFinishedAfterMaxEndTime": "Atraso no Horário Limite (Entrega)",
    "pickupDeliverySameVehicle": "Embarque/Desembarque em Veículos Diferentes",
    "pickupBeforeDelivery": "Desembarque Antes do Embarque",
    "maximizeVisitsAssigned": "Maximizar Passageiros Atendidos",
    "minimizeTravelTime": "Minimizar Tempo de Viagem",
    "penalizeEmptyVans": "Penalizar Vans Vazias"
};

function analyzeScore(solution, endpointPath) {
    new bootstrap.Modal("#scoreAnalysisModal").show()
    const scoreAnalysisModalContent = $("#scoreAnalysisModalContent");
    scoreAnalysisModalContent.children().remove();
    scoreAnalysisModalContent.text("");

    if (solution.score == null) {
        scoreAnalysisModalContent.text("A pontuação não está pronta para análise. Tente executar o solucionador primeiro ou aguarde até que ele avance.");
    } else {
        visualizeScoreAnalysis(scoreAnalysisModalContent, solution, endpointPath)
    }
}

function visualizeScoreAnalysis(scoreAnalysisModalContent, solution, endpointPath) {
    $('#scoreAnalysisScoreLabel').text(`(${solution.score})`);
    $.put(endpointPath, JSON.stringify(solution), function (scoreAnalysis) {
        let constraints = scoreAnalysis.constraints;
        constraints.sort(compareConstraintsBySeverity);
        constraints.map(addDerivedScoreAttributes);
        scoreAnalysis.constraints = constraints;

        const analysisTable = $(`<table class="table"/>`).css({textAlign: 'center'});
        const analysisTHead = $(`<thead/>`).append($(`<tr/>`)
            .append($(`<th></th>`))
            .append($(`<th class="text-nowrap">Restrição Aplicada <i class="fas fa-info-circle text-muted ms-1" style="cursor: help;" title="A regra de negócio avaliada."></i></th>`).css({textAlign: 'left'}))
            .append($(`<th class="text-nowrap">Tipo <i class="fas fa-info-circle text-muted ms-1" style="cursor: help;" title="Hard: Obrigatória, invalida o plano se quebrada.&#10;Medium: Regra de negócio prioritária.&#10;Soft: Flexível, busca apenas minimizar (ex: tempo)."></i></th>`))
            .append($(`<th class="text-nowrap">Ocorrências <i class="fas fa-info-circle text-muted ms-1" style="cursor: help;" title="Quantos elementos (ex: veículos ou passageiros) ativaram esta regra."></i></th>`))
            .append($(`<th class="text-nowrap">Peso <i class="fas fa-info-circle text-muted ms-1" style="cursor: help;" title="A penalidade base."></i></th>`))
            .append($(`<th class="text-nowrap">Pontuação <i class="fas fa-info-circle text-muted ms-1" style="cursor: help;" title="O impacto total.&#10;Em regras dinâmicas (como tempo), soma os segundos de cada ocorrência.&#10;Nas demais, multiplica ocorrências pelo peso."></i></th>`))
            .append($(`<th></th>`)));
        analysisTable.append(analysisTHead);
        const analysisTBody = $(`<tbody/>`)
        $.each(scoreAnalysis.constraints, function (index, constraintAnalysis) {
            visualizeConstraintAnalysis(analysisTBody, index, constraintAnalysis)
        });
        analysisTable.append(analysisTBody);
        scoreAnalysisModalContent.append(analysisTable);
    }).fail(function (xhr, ajaxOptions, thrownError) {
            showError("Score analysis failed.", xhr);
        },
        "text");
}

function compareConstraintsBySeverity(a, b) {
    let aComponents = getScoreComponents(a.score), bComponents = getScoreComponents(b.score);
    if (aComponents.hard < 0 && bComponents.hard > 0) return -1;
    if (aComponents.hard > 0 && bComponents.soft < 0) return 1;
    if (Math.abs(aComponents.hard) > Math.abs(bComponents.hard)) {
        return -1;
    } else {
        if (aComponents.medium < 0 && bComponents.medium > 0) return -1;
        if (aComponents.medium > 0 && bComponents.medium < 0) return 1;
        if (Math.abs(aComponents.medium) > Math.abs(bComponents.medium)) {
            return -1;
        } else {
            if (aComponents.soft < 0 && bComponents.soft > 0) return -1;
            if (aComponents.soft > 0 && bComponents.soft < 0) return 1;

            return Math.abs(bComponents.soft) - Math.abs(aComponents.soft);
        }
    }
}

function addDerivedScoreAttributes(constraint) {
    let components = getScoreComponents(constraint.weight);
    constraint.type = components.hard != 0 ? 'hard' : (components.medium != 0 ? 'medium' : 'soft');
    constraint.weight = components[constraint.type];
    let scores = getScoreComponents(constraint.score);
    constraint.implicitScore = scores.hard != 0 ? scores.hard : (scores.medium != 0 ? scores.medium : scores.soft);
}

function getScoreComponents(score) {
    let components = {hard: 0, medium: 0, soft: 0};

    $.each([...score.matchAll(/(-?[0-9]+)(hard|medium|soft)/g)], function (i, parts) {
        components[parts[2]] = parseInt(parts[1], 10);
    });

    return components;
}

function visualizeConstraintAnalysis(analysisTBody, constraintIndex, constraintAnalysis, recommendation = false, recommendationIndex = null) {
    let icon = constraintAnalysis.type == "hard" && constraintAnalysis.implicitScore < 0 ? '<span class="fas fa-exclamation-triangle" style="color: red"></span>' : '';
    if (!icon) icon = constraintAnalysis.weight < 0 && constraintAnalysis.matches.length == 0 ? '<span class="fas fa-check-circle" style="color: green"></span>' : '';

    let translatedName = constraintTranslations[constraintAnalysis.name] || constraintAnalysis.name;
    let row = $(`<tr/>`);
    row.append($(`<td/>`).html(icon))
        .append($(`<td/>`).text(translatedName).css({textAlign: 'left', fontWeight: '500'}))
        .append($(`<td/>`).text(constraintAnalysis.type))
        .append($(`<td/>`).html(`<b>${constraintAnalysis.matches.length}</b>`))
        .append($(`<td/>`).text(constraintAnalysis.weight))
        .append($(`<td/>`).text(recommendation ? constraintAnalysis.score : constraintAnalysis.implicitScore));

    analysisTBody.append(row);
    row.append($(`<td/>`));
}
