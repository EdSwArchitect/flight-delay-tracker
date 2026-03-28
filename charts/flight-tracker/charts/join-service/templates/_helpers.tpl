{{- define "join-service.fullname" -}}
{{- printf "%s" .Chart.Name -}}
{{- end -}}

{{- define "join-service.labels" -}}
app: {{ include "join-service.fullname" . }}
chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{- define "join-service.selectorLabels" -}}
app: {{ include "join-service.fullname" . }}
{{- end -}}
