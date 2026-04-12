{{/*
Expand the chart name.
*/}}
{{- define "task-manager.name" -}}
{{- .Chart.Name }}
{{- end }}

{{/*
Create a fully-qualified name from release name + chart name, capped at 63 chars.
*/}}
{{- define "task-manager.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}

{{/*
Common labels applied to every resource.
*/}}
{{- define "task-manager.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}

{{/*
Selector labels (stable — never change after initial deploy).
*/}}
{{- define "task-manager.selectorLabels" -}}
app.kubernetes.io/name: {{ include "task-manager.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Resolved image tag — falls back to Chart.AppVersion when values.image.tag is empty.
*/}}
{{- define "task-manager.imageTag" -}}
{{- .Values.image.tag | default .Chart.AppVersion }}
{{- end }}

{{/*
Secret name for DB credentials.
If db.existingSecret is provided, use it; otherwise use the chart-managed secret.
*/}}
{{- define "task-manager.dbSecretName" -}}
{{- if .Values.db.existingSecret }}
{{- .Values.db.existingSecret }}
{{- else }}
{{- include "task-manager.fullname" . }}-secrets
{{- end }}
{{- end }}

{{/*
Secret name for Redis credentials.
*/}}
{{- define "task-manager.redisSecretName" -}}
{{- if .Values.redis.existingSecret }}
{{- .Values.redis.existingSecret }}
{{- else }}
{{- include "task-manager.fullname" . }}-secrets
{{- end }}
{{- end }}
