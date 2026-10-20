import type { components } from './schema'

export type User = components['schemas']['User']
export type Credentials = components['schemas']['Credentials']
export type SignUpRequest = components['schemas']['SignUpRequest']
export type ApiErrorBody = components['schemas']['Error'] & { currentVersion?: number; conflictingSlug?: string }
export type ResumeDocument = components['schemas']['ResumeDocument']
export type ResumeVersionSummary = components['schemas']['ResumeVersionSummary']
export type ResumeTree = components['schemas']['ResumeTree']
export type Tag = components['schemas']['Tag']
export type TagCategory = components['schemas']['TagCategory']
export type RenderResult = components['schemas']['RenderResult']

/** Error thrown by any API client call; mirrors the contract's Error schema. */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  /** Present on 409 version_conflict: the version now stored. */
  readonly currentVersion?: number
  /** Present on 409 alias_conflict: the tag that already holds the name. */
  readonly conflictingSlug?: string
  /** Per-field problems on 400 validation_failed. */
  readonly details?: ApiErrorBody['details']

  constructor(status: number, body: ApiErrorBody) {
    super(body.message)
    this.name = 'ApiError'
    this.status = status
    this.code = body.code
    this.currentVersion = body.currentVersion
    this.conflictingSlug = body.conflictingSlug
    this.details = body.details
  }
}

/** Auth operations (openapi operationIds signUp, logIn, logOut, getMe). */
export interface AuthApi {
  signUp(credentials: SignUpRequest & Credentials): Promise<User>
  logIn(credentials: Credentials): Promise<User>
  logOut(): Promise<void>
  /** Resolves to null when nobody is signed in (401). */
  getMe(): Promise<User | null>
}

/** Master resume operations (operationIds getMasterResume, saveMasterResume, listMasterVersions, restoreMasterVersion). */
export interface ResumeApi {
  getMaster(): Promise<ResumeDocument>
  /** 409 rejects with an ApiError carrying currentVersion. */
  saveMaster(baseVersion: number, tree: ResumeTree): Promise<ResumeDocument>
  listVersions(): Promise<ResumeVersionSummary[]>
  restoreVersion(version: number, baseVersion: number): Promise<ResumeDocument>
}

/** Render operation (operationId renderResume). Takes the tree, not LaTeX; the PDF itself is fetched via result.pdfUrl. */
export interface RenderApi {
  render(tree: ResumeTree): Promise<RenderResult>
}

/** Tag vocabulary (operationIds listTags, createTag). Aliases are not managed by the node tag editor. */
export interface TagsApi {
  listTags(): Promise<Tag[]>
  /** Get or create: resolves to the existing tag (200) or the new one (201). */
  createTag(name: string, category: TagCategory): Promise<Tag>
}

export interface Api {
  tags: TagsApi
  auth: AuthApi
  resume: ResumeApi
  render: RenderApi
}
