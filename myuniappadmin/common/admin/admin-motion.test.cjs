const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const projectRoot = path.resolve(__dirname, '..', '..')
const read = relativePath => fs.readFileSync(path.join(projectRoot, relativePath), 'utf8')

test('motion module exposes cancelable spring presets without a third-party dependency', () => {
	const motion = read('common/admin/admin-motion.js')
	const packageJson = JSON.parse(read('package.json'))

	assert.match(motion, /ADMIN_MOTION_PRESETS/)
	assert.match(motion, /quiet:\s*Object\.freeze\(\{\s*dampingRatio:\s*1/s)
	assert.match(motion, /sheet:\s*Object\.freeze\(\{\s*dampingRatio:\s*0\.85/s)
	assert.match(motion, /response:\s*0\.34/)
	assert.match(motion, /export function animateAdminSpring/)
	assert.match(motion, /export function cancelAdminMotion/)
	assert.match(motion, /#ifdef H5/)
	assert.match(motion, /export function adminSupportsSpringMotion/)
	assert.doesNotMatch(JSON.stringify(packageJson.dependencies), /motion|gsap|anime|spring/i)
})

test('reduced motion resolves immediately and never starts a frame loop', () => {
	const motion = read('common/admin/admin-motion.js')

	assert.match(motion, /adminPrefersReducedMotion\(\)/)
	assert.match(motion, /onUpdate\(to\)/)
	assert.match(motion, /return createCompletedMotionHandle\(\)/)
})

test('spring completion uses both displacement and velocity thresholds', () => {
	const motion = read('common/admin/admin-motion.js')

	assert.match(motion, /Math\.abs\(target - current\)\s*<=\s*precision/)
	assert.match(motion, /Math\.abs\(velocity\)\s*<=\s*precision/)
})
