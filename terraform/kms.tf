# KMS key definitions for per-subject envelope encryption (WO-193)
#
# Key hierarchy:
#   app_master_key  ← wraps per-subject AES-256 data keys stored in subject_data_key
#   blind_index_key ← used ONLY for HMAC-SHA-256 blind-index computation; never wraps data keys
#
# Policy: least-privilege — the application task role is granted only:
#   kms:GenerateDataKey   (to create new per-subject data keys)
#   kms:Decrypt           (to unwrap existing per-subject data keys)
#   kms:ScheduleKeyDeletion  (for lawful erasure WO-095; scoped to ops role only)
#
# No key material appears in state beyond ARNs.
# Annual automatic rotation is enabled on both keys (AWS manages rotation internally;
# key IDs and aliases remain stable so no application changes are needed).

data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  region     = data.aws_region.current.name
  app_task_role_arn = "arn:aws:iam::${local.account_id}:role/field-service-app-task"
  ops_role_arn      = "arn:aws:iam::${local.account_id}:role/field-service-ops"
}

# ---- Application master key (wraps per-subject data keys) ------------------

resource "aws_kms_key" "app_master_key" {
  description             = "Field-service application master key — wraps per-subject AES-256 data keys"
  key_usage               = "ENCRYPT_DECRYPT"
  customer_master_key_spec = "SYMMETRIC_DEFAULT"
  enable_key_rotation     = true
  deletion_window_in_days = 30

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "RootFullAccess"
        Effect = "Allow"
        Principal = { AWS = "arn:aws:iam::${local.account_id}:root" }
        Action   = "kms:*"
        Resource = "*"
      },
      {
        Sid    = "AppTaskRoleDataKeyOps"
        Effect = "Allow"
        Principal = { AWS = local.app_task_role_arn }
        Action = [
          "kms:GenerateDataKey",
          "kms:Decrypt",
          "kms:DescribeKey"
        ]
        Resource = "*"
      },
      {
        Sid    = "OpsKeyDeletion"
        Effect = "Allow"
        Principal = { AWS = local.ops_role_arn }
        Action = [
          "kms:ScheduleKeyDeletion",
          "kms:CancelKeyDeletion",
          "kms:DescribeKey"
        ]
        Resource = "*"
      }
    ]
  })

  tags = {
    Name        = "field-service-app-master-key"
    Environment = terraform.workspace
    ManagedBy   = "terraform"
    Purpose     = "per-subject-data-key-wrapping"
  }
}

resource "aws_kms_alias" "app_master_key" {
  name          = "alias/field-service/app-master-key"
  target_key_id = aws_kms_key.app_master_key.key_id
}

# ---- Blind-index key (HMAC-SHA-256 only; distinct from master key) ----------

resource "aws_kms_key" "blind_index_key" {
  description              = "Field-service blind-index key — HMAC-SHA-256 for encrypted-column equality lookup"
  key_usage                = "GENERATE_VERIFY_MAC"
  customer_master_key_spec = "HMAC_256"
  enable_key_rotation      = false  # HMAC keys do not support automatic rotation
  deletion_window_in_days  = 30

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "RootFullAccess"
        Effect = "Allow"
        Principal = { AWS = "arn:aws:iam::${local.account_id}:root" }
        Action   = "kms:*"
        Resource = "*"
      },
      {
        Sid    = "AppTaskRoleHmacOps"
        Effect = "Allow"
        Principal = { AWS = local.app_task_role_arn }
        Action = [
          "kms:GenerateMac",
          "kms:VerifyMac",
          "kms:DescribeKey"
        ]
        Resource = "*"
      }
    ]
  })

  tags = {
    Name        = "field-service-blind-index-key"
    Environment = terraform.workspace
    ManagedBy   = "terraform"
    Purpose     = "blind-index-hmac"
  }
}

resource "aws_kms_alias" "blind_index_key" {
  name          = "alias/field-service/blind-index-key"
  target_key_id = aws_kms_key.blind_index_key.key_id
}

# ---- Outputs (ARNs only; no key material) ----------------------------------

output "app_master_key_arn" {
  description = "ARN of the application master key for per-subject data-key wrapping"
  value       = aws_kms_key.app_master_key.arn
}

output "blind_index_key_arn" {
  description = "ARN of the blind-index HMAC key"
  value       = aws_kms_key.blind_index_key.arn
}
