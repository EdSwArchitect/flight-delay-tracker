{{- define "api.fullname" -}}
{{- printf "%s" .Chart.Name -}}
{{- end -}}

{{- define "api.labels" -}}
app: {{ include "api.fullname" . }}
chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{- define "api.selectorLabels" -}}
app: {{ include "api.fullname" . }}
{{- end -}}
