<script>
	import {
		version
	} from './package.json'
	// #ifdef APP
	import checkUpdate from '@/uni_modules/uni-upgrade-center-app/utils/check-update';
	// #endif

	export default {
		onLaunch: function() {
			// #ifdef H5
			console.log(
				`%c AI Temperate %c v${version} `,
				'background:#10251d ; padding: 1px; border-radius: 3px 0 0 3px;  color: #dff8ed',
				'background:#37d39a ;padding: 1px; border-radius: 0 3px 3px 0;  color: #04110c; font-weight: bold;'
			)
			// #endif
			console.log('App Launch');
			// #ifdef APP-PLUS
			// App平台检测升级，服务端代码是通过uniCloud的云函数实现的，详情可参考：https://ext.dcloud.net.cn/plugin?id=4542
			if (plus.runtime.appid !== 'HBuilder') { // 真机运行不需要检查更新，真机运行时appid固定为'HBuilder'，这是调试基座的appid
				checkUpdate()
			}
			// #endif
		},
		onShow: function() {
			console.log('App Show')
		},
		onHide: function() {
			console.log('App Hide')
		},
		globalData: {
			test: ''
		}
	}
</script>

<style lang="scss">
	@import '@/uni_modules/uni-scss/index.scss';
	@import './common/app-theme.scss';
	/* #ifndef APP-PLUS-NVUE */
	/* #ifdef H5 */
	@media screen and (min-width: 768px) {
		body {
			overflow-y: scroll;
		}
	}

	uni-page-body {
		background-color: #0b0d0c !important;
		min-height: 100% !important;
		height: auto !important;
	}

	/* #endif */

	page {
		background-color: #0b0d0c;
		color: #f3f5f4;
		height: 100%;
		font-size: 28rpx;
	}

	/* #endif*/
</style>
