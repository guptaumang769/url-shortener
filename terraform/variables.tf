variable "region" {
  description = "AWS region"
  type        = string
  default     = "ap-south-1" # Mumbai
}

variable "project" {
  type    = string
  default = "url-shortener"
}

variable "db_password" {
  description = "RDS master password (pass via TF_VAR_db_password or a tfvars file — never commit)"
  type        = string
  sensitive   = true
}

variable "container_image" {
  description = "ECR image URI for the app (e.g. <acct>.dkr.ecr.<region>.amazonaws.com/url-shortener:latest)"
  type        = string
}

variable "desired_count" {
  type    = number
  default = 2
}

variable "enable_read_replica" {
  description = "Provision an RDS read replica for the read-heavy redirect path"
  type        = bool
  default     = false # off by default to keep the demo cheap; turn on to show read scaling
}
