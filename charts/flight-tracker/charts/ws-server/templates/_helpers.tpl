{{- define "ws-server.fullname" -}}
{{- printf "%s" .Chart.Name -}}
{{- end -}}

{{- define "ws-server.labels" -}}
app: {{ include "ws-server.fullname" . }}
chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{- define "ws-server.selectorLabels" -}}
app: {{ include "ws-server.fullname" . }}
{{- end -}}
