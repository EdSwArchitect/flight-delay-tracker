{{- define "map-ui.fullname" -}}
{{- printf "%s" .Chart.Name -}}
{{- end -}}

{{- define "map-ui.labels" -}}
app: {{ include "map-ui.fullname" . }}
chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{- define "map-ui.selectorLabels" -}}
app: {{ include "map-ui.fullname" . }}
{{- end -}}
