<template>
	<view>
		<identifier-fields
			type="EMAIL"
			:email="email"
			:errors="errors"
			@update:email="$emit('update:email', $event)"
		/>
		<identifier-fields
			type="PHONE"
			:country-id="countryId"
			:country-resolving="countryResolving"
			:phone="phone"
			:errors="errors"
			@update:country-id="$emit('update:countryId', $event)"
			@update:phone="$emit('update:phone', $event)"
		/>
	</view>
</template>

<script>
	import IdentifierFields from '@/components/auth/identifier-fields.vue'

	export default {
		name: 'AdminIdentityForm',
		components: { IdentifierFields },
		emits: ['update:email', 'update:countryId', 'update:phone'],
		props: {
			email: { type: String, default: '' },
			countryId: { type: String, default: '' },
			countryResolving: { type: Boolean, default: false },
			phone: { type: String, default: '' },
			errors: { type: Object, default: () => ({}) }
		}
	}
</script>
