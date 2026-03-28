{{- define "airlabs-poller.fullname" -}}
{{- printf "%s" .Chart.Name -}}
{{- end -}}

{{- define "airlabs-poller.labels" -}}
app: {{ include "airlabs-poller.fullname" . }}
chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{- define "airlabs-poller.selectorLabels" -}}
app: {{ include "airlabs-poller.fullname" . }}
{{- end -}}
