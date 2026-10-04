import { request } from './http'
import type { ApiId } from './ids'
import type {
  RiskAssessmentRequest,
  RiskAssessmentResponse,
  RiskReviewCase,
  RiskReviewResolveRequest,
} from '@/types'

export function assessRisk(payload: RiskAssessmentRequest): Promise<RiskAssessmentResponse> {
  return request<RiskAssessmentResponse>({ url: '/risk/assess', method: 'POST', data: payload })
}

export function riskReviews(): Promise<RiskReviewCase[]> {
  return request<RiskReviewCase[]>({ url: '/risk/reviews' })
}

export function resolveRiskReview(
  caseId: ApiId,
  payload: RiskReviewResolveRequest,
): Promise<RiskReviewCase> {
  return request<RiskReviewCase>({
    url: `/risk/reviews/${caseId}/resolve`,
    method: 'POST',
    data: payload,
  })
}
