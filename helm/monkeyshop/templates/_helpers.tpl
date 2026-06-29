{{- define "monkeyshop.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "monkeyshop.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "monkeyshop.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "monkeyshop.labels" -}}
helm.sh/chart: {{ include "monkeyshop.chart" . }}
{{ include "monkeyshop.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: monkeyshop
app.kubernetes.io/environment: {{ .Values.global.environment | quote }}
{{- end -}}

{{- define "monkeyshop.selectorLabels" -}}
app.kubernetes.io/name: {{ include "monkeyshop.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "monkeyshop.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "monkeyshop.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{- define "monkeyshop.secretName" -}}
{{- default (printf "%s-runtime" (include "monkeyshop.fullname" .)) .Values.secret.existingSecret -}}
{{- end -}}

{{- define "monkeyshop.namespaceName" -}}
{{- default .Release.Namespace .Values.namespace.name -}}
{{- end -}}

