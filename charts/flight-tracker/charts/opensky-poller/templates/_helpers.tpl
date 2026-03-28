{{- define "opensky-poller.fullname" -}}
{{- printf "%s" .Chart.Name -}}
{{- end -}}

{{- define "opensky-poller.labels" -}}
app: {{ include "opensky-poller.fullname" . }}
chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{- define "opensky-poller.selectorLabels" -}}
app: {{ include "opensky-poller.fullname" . }}
{{- end -}}
