import { randomBytes, createHash } from 'node:crypto';
const token = randomBytes(32).toString('base64url');
const hash = createHash('sha256').update(token).digest('hex');
console.log(
  'Device access key for the owner’s phone (show privately once)\n' + token
);
console.log('\nDEVICE_TOKEN_SHA256 for the server environment\n' + hash);
