/**
 * ContextCard — displays site, contact, asset and fault context for a job.
 *
 * Designed for 360 px one-handed viewport. Long text (fault description,
 * access notes) truncates with an expand toggle to avoid breaking the layout.
 *
 * @module app/technician/components/ContextCard
 */

import React, { useState } from 'react';
import styles from './ContextCard.module.css';

const MAX_VISIBLE_CHARS = 200;

function ExpandableText({ text }) {
  const [expanded, setExpanded] = useState(false);
  if (!text) return null;
  if (text.length <= MAX_VISIBLE_CHARS) return <p className={styles.text}>{text}</p>;

  return (
    <p className={styles.text}>
      {expanded ? text : `${text.slice(0, MAX_VISIBLE_CHARS)}…`}
      <button
        type="button"
        className={styles.expandBtn}
        onClick={() => setExpanded((e) => !e)}
        aria-expanded={expanded}
      >
        {expanded ? ' Show less' : ' Show more'}
      </button>
    </p>
  );
}

/**
 * @param {{
 *   job: import('../useJobDetail.js').JobDetail
 * }} props
 */
export function ContextCard({ job }) {
  return (
    <section className={styles.card} aria-label="Job context">
      <h2 className={styles.section}>Site</h2>
      <p className={styles.siteName}>{job.siteName}</p>
      {job.siteAddress && <p className={styles.text}>{job.siteAddress}</p>}
      {job.siteAccessNotes && (
        <>
          <p className={styles.label}>Access notes</p>
          <ExpandableText text={job.siteAccessNotes} />
        </>
      )}

      {(job.contactName || job.contactPhoneMasked) && (
        <>
          <h2 className={styles.section}>Contact</h2>
          {job.contactName && <p className={styles.text}>{job.contactName}</p>}
          {job.contactPhoneMasked && (
            <p className={styles.phone}>{job.contactPhoneMasked}</p>
          )}
        </>
      )}

      {(job.assetTag || job.assetDescription) && (
        <>
          <h2 className={styles.section}>Asset</h2>
          {job.assetTag && <p className={styles.assetTag}>{job.assetTag}</p>}
          {job.assetDescription && <p className={styles.text}>{job.assetDescription}</p>}
        </>
      )}

      <h2 className={styles.section}>Fault</h2>
      <ExpandableText text={job.faultSummary} />
      {job.faultCode && (
        <p className={styles.faultCode}>{job.faultCode}
          {job.faultCategory && <span className={styles.faultCat}> · {job.faultCategory}</span>}
        </p>
      )}

      {job.requiredCertifications?.length > 0 && (
        <>
          <h2 className={styles.section}>Required certifications</h2>
          <ul className={styles.list}>
            {job.requiredCertifications.map((c) => (
              <li key={c} className={styles.listItem}>{c}</li>
            ))}
          </ul>
        </>
      )}

      {job.expectedParts?.length > 0 && (
        <>
          <h2 className={styles.section}>Expected parts</h2>
          <ul className={styles.list}>
            {job.expectedParts.map((p) => (
              <li key={p.partNumber} className={styles.listItem}>
                {p.partNumber} — {p.name} × {p.quantityRequired}
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}
